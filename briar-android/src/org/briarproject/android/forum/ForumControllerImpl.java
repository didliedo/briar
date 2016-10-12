package org.briarproject.android.forum;

import android.app.Activity;
import android.support.annotation.Nullable;

import org.briarproject.android.api.BackgroundExecutor;
import org.briarproject.android.controller.DbControllerImpl;
import org.briarproject.android.controller.handler.ResultExceptionHandler;
import org.briarproject.android.controller.handler.ResultHandler;
import org.briarproject.api.FormatException;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.CryptoExecutor;
import org.briarproject.api.crypto.KeyParser;
import org.briarproject.api.crypto.PrivateKey;
import org.briarproject.api.db.DbException;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.ForumPostReceivedEvent;
import org.briarproject.api.event.GroupRemovedEvent;
import org.briarproject.api.forum.Forum;
import org.briarproject.api.forum.ForumManager;
import org.briarproject.api.forum.ForumPost;
import org.briarproject.api.forum.ForumPostFactory;
import org.briarproject.api.forum.ForumPostHeader;
import org.briarproject.api.identity.IdentityManager;
import org.briarproject.api.identity.LocalAuthor;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;
import org.briarproject.util.StringUtils;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.api.identity.Author.Status.OURSELVES;

public class ForumControllerImpl extends DbControllerImpl
		implements ForumController, EventListener {

	private static final Logger LOG =
			Logger.getLogger(ForumControllerImpl.class.getName());

	private final Executor cryptoExecutor;
	private final ForumPostFactory forumPostFactory;
	private final CryptoComponent crypto;
	private final ForumManager forumManager;
	private final EventBus eventBus;
	private final IdentityManager identityManager;

	private final Map<MessageId, byte[]> bodyCache = new ConcurrentHashMap<>();
	private final AtomicLong newestTimeStamp = new AtomicLong();

	private volatile GroupId groupId;
	private volatile Forum forum;
	private volatile LocalAuthor localAuthor;
	private volatile ForumPostListener listener;

	@Inject
	ForumControllerImpl(@BackgroundExecutor Executor bgExecutor,
			LifecycleManager lifecycleManager,
			@CryptoExecutor Executor cryptoExecutor,
			ForumPostFactory forumPostFactory, CryptoComponent crypto,
			ForumManager forumManager, EventBus eventBus,
			IdentityManager identityManager) {
		super(bgExecutor, lifecycleManager);
		this.cryptoExecutor = cryptoExecutor;
		this.forumPostFactory = forumPostFactory;
		this.crypto = crypto;
		this.forumManager = forumManager;
		this.eventBus = eventBus;
		this.identityManager = identityManager;
	}

	@Override
	public void onActivityCreate(Activity activity) {
		if (activity instanceof ForumPostListener) {
			listener = (ForumPostListener) activity;
		} else {
			throw new IllegalStateException(
					"An activity that injects the ForumController must " +
							"implement the ForumPostListener");
		}
	}

	@Override
	public void onActivityStart() {
		eventBus.addListener(this);
	}

	@Override
	public void onActivityStop() {
		eventBus.removeListener(this);
	}

	@Override
	public void onActivityDestroy() {
	}

	@Override
	public void eventOccurred(Event e) {
		if (forum == null) return;
		if (e instanceof ForumPostReceivedEvent) {
			final ForumPostReceivedEvent pe = (ForumPostReceivedEvent) e;
			if (pe.getGroupId().equals(forum.getId())) {
				LOG.info("Forum post received");
				ForumPostHeader h = pe.getForumPostHeader();
				updateNewestTimestamp(h.getTimestamp());
				onForumPostReceived(h);
			}
		} else if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent g = (GroupRemovedEvent) e;
			if (g.getGroup().getId().equals(forum.getId())) {
				LOG.info("Forum removed");
				onForumRemoved();
			}
		}
	}

	private void onForumPostReceived(final ForumPostHeader h) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				listener.runOnUiThreadUnlessDestroyed(new Runnable() {
					@Override
					public void run() {
						listener.onForumPostReceived(h);
					}
				});
			}
		});
	}

	private void onForumRemoved() {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				listener.runOnUiThreadUnlessDestroyed(new Runnable() {
					@Override
					public void run() {
						listener.onForumRemoved();
					}
				});
			}
		});
	}

	@BackgroundExecutor
	private void loadForum(GroupId g) throws DbException {
		// Get Forum
		long now = System.currentTimeMillis();
		forum = forumManager.getForum(g);
		long duration = System.currentTimeMillis() - now;
		if (LOG.isLoggable(INFO))
			LOG.info("Loading forum took " + duration + " ms");

		// Get First Identity
		now = System.currentTimeMillis();
		localAuthor = identityManager.getLocalAuthor();
		duration = System.currentTimeMillis() - now;
		if (LOG.isLoggable(INFO))
			LOG.info("Loading author took " + duration + " ms");
	}

	@BackgroundExecutor
	private Collection<ForumPostHeader> loadHeaders(GroupId g)
			throws DbException {
		// Get Headers
		long now = System.currentTimeMillis();
		Collection<ForumPostHeader> headers = forumManager.getPostHeaders(g);
		long duration = System.currentTimeMillis() - now;
		if (LOG.isLoggable(INFO))
			LOG.info("Loading headers took " + duration + " ms");
		return headers;
	}

	@BackgroundExecutor
	private void loadBodies(Collection<ForumPostHeader> headers)
			throws DbException {
		// Get Bodies
		long now = System.currentTimeMillis();
		for (ForumPostHeader header : headers) {
			if (!bodyCache.containsKey(header.getId())) {
				byte[] body = forumManager.getPostBody(header.getId());
				bodyCache.put(header.getId(), body);
			}
		}
		long duration = System.currentTimeMillis() - now;
		if (LOG.isLoggable(INFO))
			LOG.info("Loading bodies took " + duration + " ms");
	}

	private List<ForumEntry> buildForumEntries(
			Collection<ForumPostHeader> headers) {
		List<ForumEntry> entries = new ArrayList<>();
		for (ForumPostHeader h : headers) {
			byte[] body = bodyCache.get(h.getId());
			entries.add(new ForumEntry(h, StringUtils.fromUtf8(body)));
		}
		return entries;
	}

	private void updateNewestTimeStamp(Collection<ForumPostHeader> headers) {
		for (ForumPostHeader h : headers) {
			updateNewestTimestamp(h.getTimestamp());
		}
	}

	@Override
	public void setGroupId(GroupId g) {
		groupId = g;
	}

	@Override
	public void loadForum(
			final ResultExceptionHandler<List<ForumEntry>, DbException> resultHandler) {
		if (groupId == null) throw new IllegalStateException();
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				LOG.info("Loading forum...");
				try {
					if (forum == null) loadForum(groupId);
					// Get Forum Posts and Bodies
					Collection<ForumPostHeader> headers = loadHeaders(groupId);
					updateNewestTimeStamp(headers);
					loadBodies(headers);
					resultHandler.onResult(buildForumEntries(headers));
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					resultHandler.onException(e);
				}
			}
		});
	}

	@Override
	@Nullable
	public Forum getForum() {
		return forum;
	}

	@Override
	public void loadPost(final ForumPostHeader header,
			final ResultExceptionHandler<ForumEntry, DbException> resultHandler) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				LOG.info("Loading post...");
				try {
					loadBodies(Collections.singletonList(header));
					resultHandler.onResult(new ForumEntry(header, StringUtils
							.fromUtf8(bodyCache.get(header.getId()))));
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					resultHandler.onException(e);
				}
			}
		});
	}

	@Override
	public void unsubscribe(final ResultHandler<Boolean> resultHandler) {
		if (groupId == null) throw new IllegalStateException();
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (forum == null) forum = forumManager.getForum(groupId);
					forumManager.removeForum(forum);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Removing forum took " + duration + " ms");
					resultHandler.onResult(true);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					resultHandler.onResult(false);
				}
			}
		});
	}

	@Override
	public void entryRead(ForumEntry forumEntry) {
		entriesRead(Collections.singletonList(forumEntry));
	}

	@Override
	public void entriesRead(final Collection<ForumEntry> forumEntries) {
		if (groupId == null) throw new IllegalStateException();
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					for (ForumEntry fe : forumEntries) {
						forumManager.setReadFlag(groupId, fe.getId(), true);
					}
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Marking read took " + duration + " ms");
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	@Override
	public void createPost(byte[] body,
			ResultExceptionHandler<ForumEntry, DbException> resultHandler) {
		createPost(body, null, resultHandler);
	}

	@Override
	public void createPost(final byte[] body, final MessageId parentId,
			final ResultExceptionHandler<ForumEntry, DbException> resultHandler) {
		if (groupId == null) throw new IllegalStateException();
		cryptoExecutor.execute(new Runnable() {
			@Override
			public void run() {
				LOG.info("Create post...");
				long timestamp = System.currentTimeMillis();
				timestamp = Math.max(timestamp, newestTimeStamp.get());
				ForumPost p;
				try {
					KeyParser keyParser = crypto.getSignatureKeyParser();
					byte[] b = localAuthor.getPrivateKey();
					PrivateKey authorKey = keyParser.parsePrivateKey(b);
					p = forumPostFactory.createPseudonymousPost(groupId,
							timestamp, parentId, localAuthor, "text/plain",
							body, authorKey);
				} catch (GeneralSecurityException | FormatException e) {
					throw new RuntimeException(e);
				}
				bodyCache.put(p.getMessage().getId(), body);
				storePost(p, resultHandler);
			}
		});
	}

	private void storePost(final ForumPost p,
			final ResultExceptionHandler<ForumEntry, DbException> resultHandler) {
		runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					LOG.info("Store post...");
					long now = System.currentTimeMillis();
					forumManager.addLocalPost(p);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Storing message took " + duration + " ms");

					ForumPostHeader h =
							new ForumPostHeader(p.getMessage().getId(),
									p.getParent(),
									p.getMessage().getTimestamp(),
									p.getAuthor(), OURSELVES, true);

					resultHandler.onResult(new ForumEntry(h, StringUtils
							.fromUtf8(bodyCache.get(p.getMessage().getId()))));

				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					resultHandler.onException(e);
				}
			}
		});
	}

	private void updateNewestTimestamp(long update) {
		long newest = newestTimeStamp.get();
		while (newest < update) {
			if (newestTimeStamp.compareAndSet(newest, update)) return;
			newest = newestTimeStamp.get();
		}
	}
}
