package org.briarproject.android.privategroup.list;

import org.briarproject.api.clients.MessageTracker.GroupCount;
import org.briarproject.api.identity.Author;
import org.briarproject.api.privategroup.PrivateGroup;
import org.briarproject.api.sync.GroupId;
import org.jetbrains.annotations.NotNull;

// This class is not thread-safe
class GroupItem {

	private final PrivateGroup privateGroup;
	private int messageCount, unreadCount;
	private long timestamp;
	private boolean dissolved;

	GroupItem(@NotNull PrivateGroup privateGroup, @NotNull GroupCount count,
			boolean dissolved) {
		this.privateGroup = privateGroup;
		this.dissolved = dissolved;
		setGroupCount(count);
	}

	void setGroupCount(GroupCount count) {
		messageCount = count.getMsgCount();
		unreadCount = count.getUnreadCount();
		timestamp = count.getLatestMsgTime();
	}

	@NotNull
	PrivateGroup getPrivateGroup() {
		return privateGroup;
	}

	@NotNull
	GroupId getId() {
		return privateGroup.getId();
	}

	@NotNull
	Author getCreator() {
		return privateGroup.getAuthor();
	}

	@NotNull
	String getName() {
		return privateGroup.getName();
	}

	boolean isEmpty() {
		return messageCount == 0;
	}

	int getMessageCount() {
		return messageCount;
	}

	long getTimestamp() {
		return timestamp;
	}

	int getUnreadCount() {
		return unreadCount;
	}

	boolean isDissolved() {
		return dissolved;
	}

}
