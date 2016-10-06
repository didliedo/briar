package org.briarproject.api.clients;

import org.briarproject.api.db.DbException;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;

public interface MessageTracker {

	/**
	 * Gets the number of visible and unread messages in the group
	 * as well as the timestamp of the latest message
	 **/
	GroupCount getGroupCount(GroupId g) throws DbException;

	/**
	 * Marks a message as read or unread and updates the group counts in g.
	 **/
	void setReadFlag(GroupId g, MessageId m, boolean read) throws DbException;

	class GroupCount {
		private final int msgCount, unreadCount;
		private final long latestMsgTime;

		public GroupCount(int msgCount, int unreadCount, long latestMsgTime) {
			this.msgCount = msgCount;
			this.unreadCount = unreadCount;
			this.latestMsgTime = latestMsgTime;
		}

		public int getMsgCount() {
			return msgCount;
		}

		public int getUnreadCount() {
			return unreadCount;
		}

		public long getLatestMsgTime() {
			return latestMsgTime;
		}
	}

}
