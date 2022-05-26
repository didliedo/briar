package org.briarproject.bramble.api.mailbox;

import org.briarproject.bramble.api.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MailboxUpdateWithMailbox extends MailboxUpdate {

	private final MailboxProperties properties;

	public MailboxUpdateWithMailbox(List<MailboxVersion> clientSupports,
			MailboxProperties properties) {
		super(clientSupports, true);
		this.properties = properties;
	}

	public MailboxUpdateWithMailbox(MailboxUpdateWithMailbox o,
			List<MailboxVersion> newClientSupports) {
		this(newClientSupports, o.getMailboxProperties());
	}

	public MailboxProperties getMailboxProperties() {
		return properties;
	}
}
