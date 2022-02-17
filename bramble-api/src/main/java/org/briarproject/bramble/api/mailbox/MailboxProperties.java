package org.briarproject.bramble.api.mailbox;

import org.briarproject.bramble.api.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MailboxProperties {

	private final String onionAddress;
	private final MailboxAuthToken authToken;
	private final MailboxFolderId inboxId;
	private final MailboxFolderId outboxId;
	private final boolean owner;
	private final boolean empty;

	public MailboxProperties() {
		onionAddress = "";
		authToken = new MailboxAuthToken(new byte[0]);
		inboxId = new MailboxFolderId(new byte[0]);
		outboxId = new MailboxFolderId(new byte[0]);
		owner = false;
		empty = true;
	}

	public MailboxProperties(String onionAddress, MailboxAuthToken authToken,
			MailboxFolderId inboxId, MailboxFolderId outboxId) {
		this.onionAddress = onionAddress;
		this.authToken = authToken;
		this.inboxId = inboxId;
		this.outboxId = outboxId;
		this.owner = false;
		this.empty = false;
	}

	// TODO owner parameter should probably be removed, and owner is
	//  implicitly true in this constructor
	public MailboxProperties(String onionAddress, MailboxAuthToken authToken,
			boolean owner) {
		this.onionAddress = onionAddress;
		this.authToken = authToken;
		this.owner = owner;
		// TODO? filling out rest of things that has no meaning for "owner mailbox props"
		this.inboxId = new MailboxFolderId(new byte[0]);
		this.outboxId = new MailboxFolderId(new byte[0]);
		this.empty = false;
	}

	public String getOnionAddress() {
		return onionAddress;
	}

	public MailboxAuthToken getAuthToken() {
		return authToken;
	}

	public MailboxFolderId getInboxId() {
		return inboxId;
	}

	public MailboxFolderId getOutboxId() {
		return outboxId;
	}

	public boolean isOwner() {
		return owner;
	}

	public boolean isEmpty() {
		return empty;
	}
}
