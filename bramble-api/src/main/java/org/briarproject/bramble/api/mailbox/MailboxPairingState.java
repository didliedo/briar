package org.briarproject.bramble.api.mailbox;

public abstract class MailboxPairingState {

	public static class Pairing extends MailboxPairingState {
	}

	public static class InvalidQrCode extends MailboxPairingState {
	}

	public static class MailboxAlreadyPaired extends MailboxPairingState {
	}

	public static class ConnectionError extends MailboxPairingState {
	}

	public static class AssertionError extends MailboxPairingState {
	}
}
