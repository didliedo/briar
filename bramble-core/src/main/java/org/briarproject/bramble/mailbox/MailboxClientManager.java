package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventExecutor;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.lifecycle.ServiceException;
import org.briarproject.bramble.api.mailbox.MailboxProperties;
import org.briarproject.bramble.api.mailbox.MailboxSettingsManager;
import org.briarproject.bramble.api.mailbox.MailboxStatus;
import org.briarproject.bramble.api.mailbox.MailboxUpdate;
import org.briarproject.bramble.api.mailbox.MailboxUpdateManager;
import org.briarproject.bramble.api.mailbox.MailboxUpdateWithMailbox;
import org.briarproject.bramble.api.mailbox.MailboxVersion;
import org.briarproject.bramble.api.mailbox.event.MailboxPairedEvent;
import org.briarproject.bramble.api.mailbox.event.MailboxUnpairedEvent;
import org.briarproject.bramble.api.mailbox.event.MailboxUpdateSentEvent;
import org.briarproject.bramble.api.mailbox.event.OwnMailboxConnectionStatusEvent;
import org.briarproject.bramble.api.mailbox.event.RemoteMailboxUpdateEvent;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.mailbox.MailboxConstants.CLIENT_SUPPORTS;
import static org.briarproject.bramble.api.mailbox.MailboxHelper.getHighestCommonMajorVersion;
import static org.briarproject.bramble.api.nullsafety.NullSafety.requireNonNull;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.TorConstants.ID;
import static org.briarproject.bramble.util.LogUtils.logException;

@ThreadSafe
@NotNullByDefault
class MailboxClientManager implements Service, EventListener {

	private static final Logger LOG =
			getLogger(MailboxClientManager.class.getName());

	private final Executor eventExecutor, dbExecutor;
	private final TransactionManager db;
	private final ContactManager contactManager;
	private final PluginManager pluginManager;
	private final MailboxSettingsManager mailboxSettingsManager;
	private final MailboxUpdateManager mailboxUpdateManager;
	private final MailboxClientFactory mailboxClientFactory;
	private final TorReachabilityMonitor reachabilityMonitor;
	private final AtomicBoolean used = new AtomicBoolean(false);

	// All mutable state must only be accessed on the worker thread
	private final Map<ContactId, Updates> contactUpdates = new HashMap<>();
	private final Map<ContactId, MailboxClient> contactClients =
			new HashMap<>();
	@Nullable
	private MailboxProperties ownProperties = null;
	@Nullable
	private MailboxClient ownClient = null;
	private boolean online = false, handleEvents = false;

	@Inject
	MailboxClientManager(@EventExecutor Executor eventExecutor,
			@DatabaseExecutor Executor dbExecutor,
			TransactionManager db,
			ContactManager contactManager,
			PluginManager pluginManager,
			MailboxSettingsManager mailboxSettingsManager,
			MailboxUpdateManager mailboxUpdateManager,
			MailboxClientFactory mailboxClientFactory,
			TorReachabilityMonitor reachabilityMonitor) {
		this.eventExecutor = eventExecutor;
		this.dbExecutor = dbExecutor;
		this.db = db;
		this.contactManager = contactManager;
		this.pluginManager = pluginManager;
		this.mailboxSettingsManager = mailboxSettingsManager;
		this.mailboxUpdateManager = mailboxUpdateManager;
		this.mailboxClientFactory = mailboxClientFactory;
		this.reachabilityMonitor = reachabilityMonitor;
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		reachabilityMonitor.start();
		dbExecutor.execute(this::loadMailboxProperties);
	}

	@DatabaseExecutor
	private void loadMailboxProperties() {
		LOG.info("Loading mailbox properties");
		try {
			db.transaction(true, txn -> {
				Map<ContactId, Updates> updates = new HashMap<>();
				for (Contact c : contactManager.getContacts(txn)) {
					MailboxUpdate local = mailboxUpdateManager
							.getLocalUpdate(txn, c.getId());
					MailboxUpdate remote = mailboxUpdateManager
							.getRemoteUpdate(txn, c.getId());
					updates.put(c.getId(), new Updates(local, remote));
				}
				MailboxProperties ownProps =
						mailboxSettingsManager.getOwnMailboxProperties(txn);
				// Use a commit action so the state in memory remains
				// consistent with the state in the DB
				txn.attach(() -> initialiseState(updates, ownProps));
			});
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
	}

	@EventExecutor
	private void initialiseState(Map<ContactId, Updates> updates,
			@Nullable MailboxProperties ownProps) {
		contactUpdates.putAll(updates);
		ownProperties = ownProps;
		Plugin tor = pluginManager.getPlugin(ID);
		if (tor != null && tor.getState() == ACTIVE) {
			LOG.info("Online");
			online = true;
			createClients();
		}
		handleEvents = true;
	}

	@EventExecutor
	private void createClients() {
		LOG.info("Creating clients");
		for (Entry<ContactId, Updates> e : contactUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates u = e.getValue();
			if (isContactMailboxUsable(u.remote)) {
				// Create and start a client for the contact's mailbox
				MailboxClient contactClient = createAndStartClient(c);
				// Assign the contact to the contact's mailbox for upload
				assignContactToContactMailboxForUpload(c, contactClient, u);
				if (!isOwnMailboxUsable(ownProperties, u.remote)) {
					// We don't have a usable mailbox, so assign the contact to
					// the contact's mailbox for download too
					assignContactToContactMailboxForDownload(c,
							contactClient, u);
				}
			}
		}
		if (ownProperties == null) return;
		if (!isOwnMailboxUsable(ownProperties)) {
			LOG.warning("We have a mailbox but we can't use it");
			return;
		}
		// Create and start a client for our mailbox
		createAndStartClientForOwnMailbox();
		// Assign contacts to our mailbox for upload/download
		for (Entry<ContactId, Updates> e : contactUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates u = e.getValue();
			if (isOwnMailboxUsable(ownProperties, u.remote)) {
				// Assign the contact to our mailbox for download
				assignContactToOwnMailboxForDownload(c, u);
				if (!isContactMailboxUsable(u.remote)) {
					// The contact doesn't have a usable mailbox, so assign
					// the contact to our mailbox for upload too
					assignContactToOwnMailboxForUpload(c, u);
				}
			}
		}
	}

	@Override
	public void stopService() throws ServiceException {
		CountDownLatch latch = new CountDownLatch(1);
		eventExecutor.execute(() -> {
			handleEvents = false;
			destroyClients();
			latch.countDown();
		});
		reachabilityMonitor.destroy();
		try {
			latch.await();
		} catch (InterruptedException e) {
			throw new ServiceException(e);
		}
	}

	@EventExecutor
	private void destroyClients() {
		LOG.info("Destroying clients");
		for (MailboxClient client : contactClients.values()) {
			client.destroy();
		}
		contactClients.clear();
		destroyOwnClient();
	}

	@EventExecutor
	private void destroyOwnClient() {
		if (ownClient != null) {
			ownClient.destroy();
			ownClient = null;
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (!handleEvents) return;
		if (e instanceof TransportActiveEvent) {
			TransportActiveEvent t = (TransportActiveEvent) e;
			if (t.getTransportId().equals(ID)) onTorActive();
		} else if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			if (t.getTransportId().equals(ID)) onTorInactive();
		} else if (e instanceof MailboxPairedEvent) {
			LOG.info("Mailbox paired");
			MailboxPairedEvent m = (MailboxPairedEvent) e;
			onMailboxPaired(m.getProperties(), m.getLocalUpdates());
		} else if (e instanceof MailboxUnpairedEvent) {
			LOG.info("Mailbox unpaired");
			MailboxUnpairedEvent m = (MailboxUnpairedEvent) e;
			onMailboxUnpaired(m.getLocalUpdates());
		} else if (e instanceof MailboxUpdateSentEvent) {
			LOG.info("Contact added");
			MailboxUpdateSentEvent m = (MailboxUpdateSentEvent) e;
			onContactAdded(m.getContactId(), m.getMailboxUpdate());
		} else if (e instanceof ContactRemovedEvent) {
			LOG.info("Contact removed");
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			onContactRemoved(c.getContactId());
		} else if (e instanceof RemoteMailboxUpdateEvent) {
			LOG.info("Remote mailbox update");
			RemoteMailboxUpdateEvent r = (RemoteMailboxUpdateEvent) e;
			onRemoteMailboxUpdate(r.getContact(), r.getMailboxUpdate());
		} else if (e instanceof OwnMailboxConnectionStatusEvent) {
			OwnMailboxConnectionStatusEvent o =
					(OwnMailboxConnectionStatusEvent) e;
			onOwnMailboxConnectionStatusChanged(o.getStatus());
		}
	}

	@EventExecutor
	private void onTorActive() {
		// If we checked the plugin at startup concurrently with the plugin
		// becoming active then `online` may already be true when we receive
		// the first TransportActiveEvent, in which case ignore it
		if (online) return;
		LOG.info("Online");
		online = true;
		createClients();
	}

	@EventExecutor
	private void onTorInactive() {
		// If we checked the plugin at startup concurrently with the plugin
		// becoming inactive then `online` may already be false when we
		// receive the first TransportInactiveEvent, in which case ignore it
		if (!online) return;
		LOG.info("Offline");
		online = false;
		destroyClients();
	}

	@EventExecutor
	private void onMailboxPaired(MailboxProperties ownProps,
			Map<ContactId, MailboxUpdateWithMailbox> localUpdates) {
		for (Entry<ContactId, MailboxUpdateWithMailbox> e :
				localUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates u = contactUpdates.get(c);
			contactUpdates.put(c, new Updates(e.getValue(), u.remote));
		}
		ownProperties = ownProps;
		if (!online) return;
		if (!isOwnMailboxUsable(ownProperties)) {
			LOG.warning("We have a mailbox but we can't use it");
			return;
		}
		// Create and start a client for our mailbox
		createAndStartClientForOwnMailbox();
		// Assign contacts to our mailbox for upload/download
		for (Entry<ContactId, Updates> e : contactUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates u = e.getValue();
			boolean isOwnMailboxUsable =
					isOwnMailboxUsable(ownProperties, u.remote);
			if (isContactMailboxUsable(u.remote)) {
				// The contact has a usable mailbox, so the contact should
				// currently be assigned to the contact's mailbox for upload
				// and download
				if (isOwnMailboxUsable) {
					// Reassign the contact to our mailbox for download
					MailboxClient contactClient =
							requireNonNull(contactClients.get(c));
					contactClient.deassignContactForDownload(c);
					assignContactToOwnMailboxForDownload(c, u);
				}
				// Else the contact remains assigned to the contact's mailbox
				// for download
			} else if (isOwnMailboxUsable) {
				// The contact doesn't have a usable mailbox, so assign the
				// contact to our mailbox for upload and download
				assignContactToOwnMailboxForUpload(c, u);
				assignContactToOwnMailboxForDownload(c, u);
			}
			// Else the contact doesn't have a usable mailbox and neither do we,
			// so the contact remains unassigned for upload and download
		}
	}

	@EventExecutor
	private void onMailboxUnpaired(Map<ContactId, MailboxUpdate> localUpdates) {
		for (Entry<ContactId, MailboxUpdate> e : localUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates updates = contactUpdates.get(c);
			contactUpdates.put(c, new Updates(e.getValue(), updates.remote));
		}
		MailboxProperties oldOwnProperties = ownProperties;
		ownProperties = null;
		if (!online) return;
		// Destroy the client for our own mailbox, if any
		destroyOwnClient();
		// Reassign contacts to their own mailboxes for download where possible
		for (Entry<ContactId, Updates> e : contactUpdates.entrySet()) {
			ContactId c = e.getKey();
			Updates u = e.getValue();
			if (isContactMailboxUsable(u.remote) &&
					isOwnMailboxUsable(oldOwnProperties, u.remote)) {
				// The contact should currently be assigned to our mailbox
				// for download. Reassign the contact to the contact's
				// mailbox for download
				MailboxClient contactClient =
						requireNonNull(contactClients.get(c));
				assignContactToContactMailboxForDownload(c, contactClient, u);
			}
		}
	}

	@EventExecutor
	private void onContactAdded(ContactId c, MailboxUpdate u) {
		Updates old = contactUpdates.put(c, new Updates(u, null));
		if (old != null) throw new IllegalStateException();
		// We haven't yet received an update from the newly added contact,
		// so at this stage we don't need to assign the contact to any
		// mailboxes for upload or download
	}

	@EventExecutor
	private void onContactRemoved(ContactId c) {
		Updates updates = requireNonNull(contactUpdates.remove(c));
		if (!online) return;
		// Destroy the client for the contact's mailbox, if any
		MailboxClient client = contactClients.remove(c);
		if (client != null) client.destroy();
		// If we have a mailbox and the contact is assigned to it for upload
		// and/or download, deassign the contact
		if (ownProperties == null) return;
		if (isOwnMailboxUsable(ownProperties, updates.remote)) {
			// We have a usable mailbox, so the contact should currently be
			// assigned to our mailbox for download. Deassign the contact from
			// our mailbox for download
			requireNonNull(ownClient).deassignContactForDownload(c);
			if (!isContactMailboxUsable(updates.remote)) {
				// The contact doesn't have a usable mailbox, so the contact
				// should currently be assigned to our mailbox for upload.
				// Deassign the contact from our mailbox for upload
				requireNonNull(ownClient).deassignContactForUpload(c);
			}
		}
	}

	@EventExecutor
	private void onRemoteMailboxUpdate(ContactId c, MailboxUpdate remote) {
		Updates old = contactUpdates.get(c);
		MailboxUpdate oldRemote = old.remote;
		Updates u = new Updates(old.local, remote);
		contactUpdates.put(c, u);
		if (!online) return;
		// What may have changed?
		// * Contact's mailbox may be usable now, was unusable before
		// * Contact's mailbox may be unusable now, was usable before
		// * Contact's mailbox may have been replaced
		// * Contact's mailbox may have changed its API versions
		// * Contact may be able to use our mailbox now, was unable before
		// * Contact may be unable to use our mailbox now, was able before
		boolean wasContactMailboxUsable = isContactMailboxUsable(oldRemote);
		boolean isContactMailboxUsable = isContactMailboxUsable(remote);
		boolean wasOwnMailboxUsable =
				isOwnMailboxUsable(ownProperties, oldRemote);
		boolean isOwnMailboxUsable = isOwnMailboxUsable(ownProperties, remote);

		// Create/destroy/replace the client for the contact's mailbox if needed
		MailboxClient contactClient = null;
		boolean clientReplaced = false;
		if (isContactMailboxUsable) {
			if (wasContactMailboxUsable) {
				MailboxProperties oldProps = getMailboxProperties(oldRemote);
				MailboxProperties newProps = getMailboxProperties(remote);
				if (oldProps.equals(newProps)) {
					// The contact previously had a usable mailbox, now has
					// a usable mailbox, it's the same mailbox, and its API
					// versions haven't changed. Keep using the existing client
					contactClient = requireNonNull(contactClients.get(c));
				} else {
					// The contact previously had a usable mailbox and now has
					// a usable mailbox, but either it's a new mailbox or its
					// API versions have changed. Replace the client
					requireNonNull(contactClients.remove(c)).destroy();
					contactClient = createAndStartClient(c);
					clientReplaced = true;
				}
			} else {
				// The client didn't previously have a usable mailbox but now
				// has one. Create and start a client
				contactClient = createAndStartClient(c);
			}
		} else if (wasContactMailboxUsable) {
			// The client previously had a usable mailbox but no longer does.
			// Destroy the existing client
			requireNonNull(contactClients.remove(c)).destroy();
		}

		boolean wasAssignedToOwnMailboxForUpload =
				wasOwnMailboxUsable && !wasContactMailboxUsable;
		boolean willBeAssignedToOwnMailboxForUpload =
				isOwnMailboxUsable && !isContactMailboxUsable;

		boolean wasAssignedToContactMailboxForDownload =
				!wasOwnMailboxUsable && wasContactMailboxUsable;
		boolean willBeAssignedToContactMailboxForDownload =
				!isOwnMailboxUsable && isContactMailboxUsable;

		// Deassign the contact for upload/download if needed
		if (wasAssignedToOwnMailboxForUpload &&
				!willBeAssignedToOwnMailboxForUpload) {
			requireNonNull(ownClient).deassignContactForUpload(c);
		}
		if (wasOwnMailboxUsable && !isOwnMailboxUsable) {
			requireNonNull(ownClient).deassignContactForDownload(c);
		}
		// If the client for the contact's mailbox was replaced or destroyed
		// above then we don't need to deassign the contact for download
		if (wasAssignedToContactMailboxForDownload &&
				!willBeAssignedToContactMailboxForDownload &&
				!clientReplaced && isContactMailboxUsable) {
			requireNonNull(contactClient).deassignContactForDownload(c);
		}
		// We never need to deassign the contact from the contact's mailbox for
		// upload: this would only be needed if the contact's mailbox were no
		// longer usable, in which case the client would already have been
		// destroyed above. Thanks to the linter for spotting this

		// Assign the contact for upload/download if needed
		if (!wasAssignedToOwnMailboxForUpload &&
				willBeAssignedToOwnMailboxForUpload) {
			assignContactToOwnMailboxForUpload(c, u);
		}
		if (!wasOwnMailboxUsable && isOwnMailboxUsable) {
			assignContactToOwnMailboxForDownload(c, u);
		}
		if ((!wasContactMailboxUsable || clientReplaced) &&
				isContactMailboxUsable) {
			assignContactToContactMailboxForUpload(c, contactClient, u);
		}
		if ((!wasAssignedToContactMailboxForDownload || clientReplaced) &&
				willBeAssignedToContactMailboxForDownload) {
			assignContactToContactMailboxForDownload(c, contactClient, u);
		}
	}

	@EventExecutor
	private void onOwnMailboxConnectionStatusChanged(MailboxStatus status) {
		if (!online || ownProperties == null) return;
		List<MailboxVersion> oldServerSupports =
				ownProperties.getServerSupports();
		List<MailboxVersion> newServerSupports = status.getServerSupports();
		if (!oldServerSupports.equals(newServerSupports)) {
			LOG.info("Our mailbox's supported API versions have changed");
			// This potentially affects every assignment of contacts to
			// mailboxes for upload and download, so just rebuild the clients
			// and assignments from scratch
			destroyClients();
			createClients();
		}
	}

	@EventExecutor
	private void createAndStartClientForOwnMailbox() {
		if (ownClient != null) throw new IllegalStateException();
		ownClient = mailboxClientFactory.createOwnMailboxClient(
				reachabilityMonitor, requireNonNull(ownProperties));
		ownClient.start();
	}

	@EventExecutor
	private MailboxClient createAndStartClient(ContactId c) {
		MailboxClient client = mailboxClientFactory
				.createContactMailboxClient(reachabilityMonitor);
		MailboxClient old = contactClients.put(c, client);
		if (old != null) throw new IllegalStateException();
		client.start();
		return client;
	}

	@EventExecutor
	private void assignContactToOwnMailboxForDownload(ContactId c, Updates u) {
		MailboxProperties localProps = getMailboxProperties(u.local);
		requireNonNull(ownClient).assignContactForDownload(c,
				requireNonNull(ownProperties),
				requireNonNull(localProps.getOutboxId()));
	}

	@EventExecutor
	private void assignContactToOwnMailboxForUpload(ContactId c, Updates u) {
		MailboxProperties localProps = getMailboxProperties(u.local);
		requireNonNull(ownClient).assignContactForUpload(c,
				requireNonNull(ownProperties),
				requireNonNull(localProps.getInboxId()));
	}

	@EventExecutor
	private void assignContactToContactMailboxForDownload(ContactId c,
			MailboxClient contactClient, Updates u) {
		MailboxProperties remoteProps =
				getMailboxProperties(requireNonNull(u.remote));
		contactClient.assignContactForDownload(c, remoteProps,
				requireNonNull(remoteProps.getInboxId()));
	}

	@EventExecutor
	private void assignContactToContactMailboxForUpload(ContactId c,
			MailboxClient contactClient, Updates u) {
		MailboxProperties remoteProps =
				getMailboxProperties(requireNonNull(u.remote));
		contactClient.assignContactForUpload(c, remoteProps,
				requireNonNull(remoteProps.getOutboxId()));
	}

	private MailboxProperties getMailboxProperties(MailboxUpdate update) {
		if (!(update instanceof MailboxUpdateWithMailbox)) {
			throw new IllegalArgumentException();
		}
		MailboxUpdateWithMailbox mailbox = (MailboxUpdateWithMailbox) update;
		return mailbox.getMailboxProperties();
	}

	/**
	 * Returns true if we can use our own mailbox to communicate with the
	 * contact that sent the given update.
	 */
	private boolean isOwnMailboxUsable(
			@Nullable MailboxProperties ownProperties,
			@Nullable MailboxUpdate remote) {
		if (ownProperties == null || remote == null) return false;
		return isMailboxUsable(remote.getClientSupports(),
				ownProperties.getServerSupports());
	}

	/**
	 * Returns true if we can use the contact's mailbox to communicate with
	 * the contact that sent the given update.
	 */
	private boolean isContactMailboxUsable(@Nullable MailboxUpdate remote) {
		if (remote instanceof MailboxUpdateWithMailbox) {
			MailboxUpdateWithMailbox remoteMailbox =
					(MailboxUpdateWithMailbox) remote;
			return isMailboxUsable(remoteMailbox.getClientSupports(),
					remoteMailbox.getMailboxProperties().getServerSupports());
		}
		return false;
	}

	/**
	 * Returns true if we can communicate with a contact that has the given
	 * client-supported API versions via a mailbox with the given
	 * server-supported API versions.
	 */
	private boolean isMailboxUsable(List<MailboxVersion> contactClient,
			List<MailboxVersion> server) {
		return getHighestCommonMajorVersion(contactClient, server) >= 0
				&& getHighestCommonMajorVersion(CLIENT_SUPPORTS, server) >= 0;
	}

	/**
	 * Returns true if we're compatible with our own mailbox.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private boolean isOwnMailboxUsable(MailboxProperties ownProperties) {
		return getHighestCommonMajorVersion(CLIENT_SUPPORTS,
				ownProperties.getServerSupports()) >= 0;
	}

	private static class Updates {

		private final MailboxUpdate local;
		@Nullable
		private final MailboxUpdate remote;

		private Updates(MailboxUpdate local, @Nullable MailboxUpdate remote) {
			this.local = local;
			this.remote = remote;
		}
	}
}
