package org.briarproject.bramble.mailbox;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.mailbox.MailboxAuthToken;
import org.briarproject.bramble.api.mailbox.MailboxFolderId;
import org.briarproject.bramble.api.mailbox.MailboxPropertiesUpdate;
import org.briarproject.bramble.api.mailbox.MailboxPropertyManager;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.IOException;

import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;

public class MailboxPropertyValidatorTest extends BrambleMockTestCase {

	private final ClientHelper clientHelper = context.mock(ClientHelper.class);

	private final BdfDictionary bdfDict;
	private final MailboxPropertiesUpdate mailboxProps;
	private final Group group;
	private final Message message;
	private final MailboxPropertyValidator mpv;

	public MailboxPropertyValidatorTest() {
		// Just a dummies, clientHelper is mocked so we're not testing
		//  parseAndValidateMailboxProperties()
		bdfDict = BdfDictionary.of(new BdfEntry("foo", "bar"));
		mailboxProps = new MailboxPropertiesUpdate("baz",
				new MailboxAuthToken(getRandomId()),
				new MailboxFolderId(getRandomId()),
				new MailboxFolderId(getRandomId()));

		group = getGroup(MailboxPropertyManager.CLIENT_ID,
				MailboxPropertyManager.MAJOR_VERSION);
		message = getMessage(group.getId());

		MetadataEncoder metadataEncoder = context.mock(MetadataEncoder.class);
		Clock clock = context.mock(Clock.class);
		mpv = new MailboxPropertyValidator(clientHelper, metadataEncoder,
				clock);
	}

	@Test
	public void testValidateProperMessage() throws IOException {
		BdfList body = BdfList.of(4, bdfDict);

		context.checking(new Expectations() {{
			oneOf(clientHelper).parseAndValidateMailboxProperties(bdfDict);
			will(returnValue(mailboxProps));
		}});

		BdfDictionary result =
				mpv.validateMessage(message, group, body).getDictionary();
		assertEquals(4, result.getLong("version").longValue());
	}

	@Test(expected = FormatException.class)
	public void testValidateWrongVersionValue() throws IOException {
		BdfList body = BdfList.of(-1, bdfDict);
		mpv.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testValidateWrongVersionType() throws IOException {
		BdfList body = BdfList.of(bdfDict, bdfDict);
		mpv.validateMessage(message, group, body);
	}

}
