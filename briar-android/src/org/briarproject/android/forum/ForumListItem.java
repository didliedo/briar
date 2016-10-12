package org.briarproject.android.forum;

import org.briarproject.api.clients.MessageTracker.GroupCount;
import org.briarproject.api.forum.Forum;

// This class is NOT thread-safe
class ForumListItem {

	private final Forum forum;
	private int postCount, unread;
	private long timestamp;

	ForumListItem(Forum forum, GroupCount count) {
		this.forum = forum;
		setGroupCount(count);
	}

	void setGroupCount(GroupCount count) {
		postCount = count.getMsgCount();
		unread = count.getUnreadCount();
		timestamp = count.getLatestMsgTime();
	}

	Forum getForum() {
		return forum;
	}

	boolean isEmpty() {
		return postCount == 0;
	}

	int getPostCount() {
		return postCount;
	}

	long getTimestamp() {
		return timestamp;
	}

	int getUnreadCount() {
		return unread;
	}
}
