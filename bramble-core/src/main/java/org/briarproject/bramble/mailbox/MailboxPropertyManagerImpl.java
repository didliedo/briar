package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.mailbox.MailboxAuthToken;
import org.briarproject.bramble.api.mailbox.MailboxFolderId;
import org.briarproject.bramble.api.mailbox.MailboxProperties;
import org.briarproject.bramble.api.mailbox.MailboxPropertiesUpdate;
import org.briarproject.bramble.api.mailbox.MailboxPropertyManager;
import org.briarproject.bramble.api.mailbox.MailboxSettingsManager;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;

import java.security.SecureRandom;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class MailboxPropertyManagerImpl implements MailboxPropertyManager,
		OpenDatabaseHook, ContactHook, ClientVersioningHook,
		IncomingMessageHook {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ClientVersioningManager clientVersioningManager;
	private final MetadataParser metadataParser;
	private final ContactGroupFactory contactGroupFactory;
	private final Clock clock;
	private final MailboxSettingsManager mailboxSettingsManager;
	private final SecureRandom secureRandom;
	private final Group localGroup;

	@Inject
	MailboxPropertyManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser,
			ContactGroupFactory contactGroupFactory, Clock clock,
			MailboxSettingsManager mailboxSettingsManager,
			CryptoComponent crypto) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.clientVersioningManager = clientVersioningManager;
		this.metadataParser = metadataParser;
		this.contactGroupFactory = contactGroupFactory;
		this.clock = clock;
		this.mailboxSettingsManager = mailboxSettingsManager;
		this.secureRandom = crypto.getSecureRandom();
		localGroup = contactGroupFactory.createLocalGroup(CLIENT_ID,
				MAJOR_VERSION);
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		if (db.containsGroup(txn, localGroup.getId())) return;
		db.addGroup(txn, localGroup);
		// Set things up for any pre-existing contacts
		for (Contact c : db.getContacts(txn)) {
			addingContact(txn, c);
		}
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		// Create a group to share with the contact
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		// Apply the client's visibility to the contact group
		Visibility client = clientVersioningManager
				.getClientVisibility(txn, c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		// If we are paired, then create and send
		MailboxProperties ownProps =
				mailboxSettingsManager.getOwnMailboxProperties(txn);
		if (ownProps != null) {
			sendMessage(txn, g.getId(), 1,
					createProperties(ownProps.getOnionAddress()));
		}
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		// TODO
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		// TODO
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		// TODO
		return null;
	}

	@Override
	public void createAndSendProperties(ContactId c) throws DbException {
		try {
			db.transaction(false, txn -> {
				MailboxProperties ownProps =
						mailboxSettingsManager.getOwnMailboxProperties(txn);
				if (ownProps == null) {
					// TODO? we're not supposed to be called if we're not paired,
					//  IllegalStateException?
					return;
				}
				// TODO When a mailbox is unpaired, sendEmptyProperties() is
				//  expected to be called by mailboxmanager. So at this point, there
				//  should either not be any local update message in our
				//  contactgroup (because we were never paired), or the latest
				//  message should be the Empty update message. Right? Should/could
				//  we assert this?

				Group g = getContactGroup(db.getContact(txn, c));
				LatestUpdate latest = findLatest(txn, g.getId(), true);
				sendMessage(txn, g.getId(),
						latest == null ? 1 : latest.version + 1,
						createProperties(ownProps.getOnionAddress()));
				if (latest != null) {
					db.removeMessage(txn, latest.messageId);
				}
			});
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void sendEmptyProperties(ContactId c) {
		// TODO
	}

	private MailboxPropertiesUpdate createProperties(String ownOnionAddress) {
		return new MailboxPropertiesUpdate(ownOnionAddress,
				new MailboxAuthToken(getRandomId()),
				new MailboxFolderId(getRandomId()),
				new MailboxFolderId(getRandomId()));
	}

	private void sendMessage(Transaction txn, GroupId g, long version,
			@Nullable MailboxPropertiesUpdate p) throws DbException {
		try {
			Message m = clientHelper.createMessage(g, clock.currentTimeMillis(),
					encodeProperties(version, p));
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_VERSION, version);
			meta.put(MSG_KEY_LOCAL, true);
			clientHelper.addLocalMessage(txn, m, meta, true, false);
		} catch (FormatException e) {
			throw new RuntimeException(e);
		}
	}

	@Nullable
	private LatestUpdate findLatest(Transaction txn, GroupId g,
			boolean local) throws DbException, FormatException {
		Map<MessageId, BdfDictionary> metadata =
				clientHelper.getMessageMetadataAsDictionary(txn, g);
		for (Map.Entry<MessageId, BdfDictionary> e : metadata.entrySet()) {
			BdfDictionary meta = e.getValue();
			if (meta.getBoolean(MSG_KEY_LOCAL) == local) {
				return new LatestUpdate(e.getKey(),
						meta.getLong(MSG_KEY_VERSION));
			}
		}
		return null;
	}

	private BdfList encodeProperties(long version,
			@Nullable MailboxPropertiesUpdate p) {
		BdfDictionary dict = new BdfDictionary();
		if (p != null) {
			// TODO need to get this from p.getOnionAddress()...
			byte[] dummyOnionPubKey = {
					1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
					17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
					32
			};
			dict.put(PROP_KEY_ONIONPUBKEY, dummyOnionPubKey);
			dict.put(PROP_KEY_AUTHTOKEN, p.getAuthToken().getBytes());
			dict.put(PROP_KEY_INBOXID, p.getInboxId().getBytes());
			dict.put(PROP_KEY_OUTBOXID, p.getOutboxId().getBytes());
		}
		return BdfList.of(version, dict);
	}

	@Override
	@Nullable
	public MailboxPropertiesUpdate getRemoteProperties(ContactId c) {
		return null;
	}

	private Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID, MAJOR_VERSION,
				c);
	}

	private byte[] getRandomId() {
		byte[] b = new byte[PROP_VAL_LENGTH];
		secureRandom.nextBytes(b);
		return b;
	}

	private static class LatestUpdate {

		private final MessageId messageId;
		private final long version;

		private LatestUpdate(MessageId messageId, long version) {
			this.messageId = messageId;
			this.version = version;
		}
	}
}
