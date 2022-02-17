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

		// TODO Note: we don't do createAndSendProperties(c) here. The idea is
		//  that the mailbox pairing code, or rather the code syncing contacts
		//  to the mailbox, calls that method when it is about to add a contact
		//  to the mailbox. Because it needs to both get the props generated and
		//  sent off, as well as getting hold of them for adding the contact to
		//  the mailbox.
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		// TODO
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		// Apply the client's visibility to the contact group
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		// TODO
		return null;
	}

	@Override
	public MailboxPropertiesUpdate createAndSendProperties(ContactId c,
			String ownOnion) throws DbException {
		return db.transactionWithResult(false, txn -> {
			// TODO When a mailbox is unpaired, mailboxmanager (or so) should
			//  call sendEmptyProperties(c) for each contact that was added to
			//  the mailbox (for which createAndSendProperties(c) was called).
			//  So at this point, there should either not be any local update
			//  message in our contactgroup (because we were never paired), or
			//  the latest message should be the Empty update message. Right?
			//  Should/could we assert this?
			//  The other way around in sendEmptyProperties() itself, right.

			MailboxPropertiesUpdate p = new MailboxPropertiesUpdate(ownOnion,
					new MailboxAuthToken(getRandomId()),
					new MailboxFolderId(getRandomId()),
					new MailboxFolderId(getRandomId()));
			Group g = getContactGroup(db.getContact(txn, c));
			storeMessageReplaceLatest(txn, g.getId(), p);
			return p;
		});
	}

	@Override
	public void sendEmptyProperties(ContactId c) throws DbException {
		db.transaction(false, txn -> {
			// TODO constraint on current local message? See above
			Group g = getContactGroup(db.getContact(txn, c));
			storeMessageReplaceLatest(txn, g.getId(), null);
		});
	}

	private void storeMessageReplaceLatest(Transaction txn, GroupId g,
			@Nullable MailboxPropertiesUpdate p) throws DbException {
		try {
			LatestUpdate latest = findLatest(txn, g, true);
			storeMessage(txn, g, latest == null ? 1 : latest.version + 1, p);
			if (latest != null) {
				db.removeMessage(txn, latest.messageId);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void storeMessage(Transaction txn, GroupId g, long version,
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
	private LatestUpdate findLatest(Transaction txn, GroupId g, boolean local)
			throws DbException, FormatException {
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
			dict.put(PROP_KEY_ONIONADDRESS, p.getOnionAddress());
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
		byte[] b = new byte[PROP_BYTES_LENGTH];
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
