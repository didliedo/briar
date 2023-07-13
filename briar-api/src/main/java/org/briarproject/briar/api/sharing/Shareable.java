package org.briarproject.briar.api.sharing;

import org.briarproject.bramble.api.Nameable;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface Shareable extends Nameable {

	GroupId getId();

}
