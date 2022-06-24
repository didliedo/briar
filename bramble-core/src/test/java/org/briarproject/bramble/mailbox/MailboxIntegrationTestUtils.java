package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.WeakSingletonProvider;
import org.briarproject.bramble.api.mailbox.InvalidMailboxIdException;
import org.briarproject.bramble.api.mailbox.MailboxAuthToken;

import javax.annotation.Nonnull;
import javax.net.SocketFactory;

import okhttp3.OkHttpClient;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class MailboxIntegrationTestUtils {

	static final String URL_BASE = "http://127.0.0.1:8000";
	static final MailboxAuthToken SETUP_TOKEN;

	static {
		try {
			SETUP_TOKEN = MailboxAuthToken.fromString(
					"54686973206973206120736574757020746f6b656e20666f722042726961722e");
		} catch (InvalidMailboxIdException e) {
			throw new IllegalStateException();
		}
	}

	static WeakSingletonProvider<OkHttpClient> createHttpClientProvider() {
		OkHttpClient client = new OkHttpClient.Builder()
				.socketFactory(SocketFactory.getDefault())
				.connectTimeout(60_000, MILLISECONDS)
				.build();
		return new WeakSingletonProvider<OkHttpClient>() {
			@Override
			@Nonnull
			public OkHttpClient createInstance() {
				return client;
			}
		};
	}

	static MailboxApi createMailboxApi() {
		return new MailboxApiImpl(createHttpClientProvider(), onion -> onion);
	}
}
