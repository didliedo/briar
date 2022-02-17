package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.mailbox.MailboxPropertyManager;
import org.briarproject.bramble.api.mailbox.MailboxSettingsManager;
import org.briarproject.bramble.api.sync.validation.ValidationManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.bramble.api.mailbox.MailboxPropertyManager.CLIENT_ID;
import static org.briarproject.bramble.api.mailbox.MailboxPropertyManager.MAJOR_VERSION;
import static org.briarproject.bramble.api.mailbox.MailboxPropertyManager.MINOR_VERSION;

@Module
public class MailboxModule {

	public static class EagerSingletons {
		@Inject
		MailboxPropertyValidator mailboxPropertyValidator;
		@Inject
		MailboxPropertyManager mailboxPropertyManager;
	}

	@Provides
	MailboxSettingsManager provideMailboxSettingsManager(
			MailboxSettingsManagerImpl mailboxSettingsManager) {
		return mailboxSettingsManager;
	}

	@Provides
	@Singleton
	MailboxPropertyValidator provideValidator(
			ValidationManager validatorManager, ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		MailboxPropertyValidator validator = new MailboxPropertyValidator(
				clientHelper, metadataEncoder, clock);
		validatorManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
				validator);
		return validator;
	}

	@Provides
	@Singleton
	MailboxPropertyManager provideMailboxPropertyManager(
			LifecycleManager lifecycleManager,
			ValidationManager validationManager, ContactManager contactManager,
			ClientVersioningManager clientVersioningManager,
			MailboxPropertyManagerImpl mailboxPropertyManager) {
		lifecycleManager.registerOpenDatabaseHook(mailboxPropertyManager);
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				mailboxPropertyManager);
		contactManager.registerContactHook(mailboxPropertyManager);
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				MINOR_VERSION, mailboxPropertyManager);
		return mailboxPropertyManager;
	}

}
