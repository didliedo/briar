package org.briarproject.android.contact;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.util.Pair;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.R;
import org.briarproject.android.ActivityComponent;
import org.briarproject.android.api.AndroidNotificationManager;
import org.briarproject.android.fragment.BaseFragment;
import org.briarproject.android.keyagreement.KeyAgreementActivity;
import org.briarproject.android.view.BriarRecyclerView;
import org.briarproject.api.clients.MessageTracker.GroupCount;
import org.briarproject.api.contact.Contact;
import org.briarproject.api.contact.ContactId;
import org.briarproject.api.contact.ContactManager;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.NoSuchContactException;
import org.briarproject.api.event.ContactConnectedEvent;
import org.briarproject.api.event.ContactDisconnectedEvent;
import org.briarproject.api.event.ContactRemovedEvent;
import org.briarproject.api.event.ContactStatusChangedEvent;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.IntroductionRequestReceivedEvent;
import org.briarproject.api.event.IntroductionResponseReceivedEvent;
import org.briarproject.api.event.InvitationRequestReceivedEvent;
import org.briarproject.api.event.InvitationResponseReceivedEvent;
import org.briarproject.api.event.PrivateMessageReceivedEvent;
import org.briarproject.api.identity.IdentityManager;
import org.briarproject.api.identity.LocalAuthor;
import org.briarproject.api.messaging.ConversationManager;
import org.briarproject.api.plugins.ConnectionRegistry;
import org.briarproject.api.sync.GroupId;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;

import static android.support.v4.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.BriarActivity.GROUP_ID;

public class ContactListFragment extends BaseFragment implements EventListener {

	public static final String TAG = ContactListFragment.class.getName();
	private static final Logger LOG = Logger.getLogger(TAG);

	@Inject
	ConnectionRegistry connectionRegistry;
	@Inject
	EventBus eventBus;
	@Inject
	AndroidNotificationManager notificationManager;

	private ContactListAdapter adapter;
	private BriarRecyclerView list;

	// Fields that are accessed from background threads must be volatile
	@Inject
	volatile ContactManager contactManager;
	@Inject
	volatile IdentityManager identityManager;
	@Inject
	volatile ConversationManager conversationManager;

	public static ContactListFragment newInstance() {
		Bundle args = new Bundle();
		ContactListFragment fragment = new ContactListFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {

		setHasOptionsMenu(true);

		View contentView =
				inflater.inflate(R.layout.list, container,
						false);

		BaseContactListAdapter.OnItemClickListener onItemClickListener =
				new ContactListAdapter.OnItemClickListener() {
					@Override
					public void onItemClick(View view, ContactListItem item) {
						GroupId groupId = item.getGroupId();
						Intent i = new Intent(getActivity(),
								ConversationActivity.class);
						i.putExtra(GROUP_ID, groupId.getBytes());

						ContactListAdapter.ContactHolder holder =
								(ContactListAdapter.ContactHolder) list
										.getRecyclerView()
										.findViewHolderForAdapterPosition(
												adapter.findItemPosition(item));
						Pair<View, String> avatar =
								Pair.create((View) holder.avatar, ViewCompat
										.getTransitionName(holder.avatar));
						Pair<View, String> bulb =
								Pair.create((View) holder.bulb, ViewCompat
										.getTransitionName(holder.bulb));
						ActivityOptionsCompat options =
								makeSceneTransitionAnimation(getActivity(),
										avatar, bulb);
						ActivityCompat.startActivity(getActivity(), i,
								options.toBundle());
					}
				};

		adapter = new ContactListAdapter(getContext(), onItemClickListener);
		list = (BriarRecyclerView) contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(getContext()));
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.no_contacts));

		return contentView;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.contact_list_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		// Handle presses on the action bar items
		switch (item.getItemId()) {
			case R.id.action_add_contact:
				Intent intent =
						new Intent(getContext(), KeyAgreementActivity.class);
				startActivity(intent);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		notificationManager.blockAllContactNotifications();
		notificationManager.clearAllContactNotifications();
		eventBus.addListener(this);
		loadContacts(false);
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		notificationManager.unblockAllContactNotifications();
		adapter.clear();
		list.showProgressBar();
		list.stopPeriodicUpdate();
	}

	private void loadContacts(final boolean clear) {
		listener.runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					List<ContactListItem> contacts = new ArrayList<>();
					for (Contact c : contactManager.getActiveContacts()) {
						try {
							ContactId id = c.getId();
							GroupId groupId =
									conversationManager.getConversationId(id);
							GroupCount count =
									conversationManager.getGroupCount(id);
							boolean connected =
									connectionRegistry.isConnected(c.getId());
							LocalAuthor localAuthor = identityManager
									.getLocalAuthor(c.getLocalAuthorId());
							contacts.add(new ContactListItem(c, localAuthor,
									connected, groupId, count));
						} catch (NoSuchContactException e) {
							// Continue
						}
					}
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Full load took " + duration + " ms");
					displayContacts(contacts, clear);
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void displayContacts(final List<ContactListItem> contacts,
			final boolean clear) {
		listener.runOnUiThreadUnlessDestroyed(new Runnable() {
			@Override
			public void run() {
				if (clear) adapter.setItems(contacts);
				else adapter.addAll(contacts);
				if (contacts.isEmpty()) list.showData();
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactStatusChangedEvent) {
			LOG.info("Contact status changed, reloading");
			ContactStatusChangedEvent c = (ContactStatusChangedEvent) e;
			loadContacts(!c.isActive());
		} else if (e instanceof ContactConnectedEvent) {
			setConnected(((ContactConnectedEvent) e).getContactId(), true);
		} else if (e instanceof ContactDisconnectedEvent) {
			setConnected(((ContactDisconnectedEvent) e).getContactId(), false);
		} else if (e instanceof ContactRemovedEvent) {
			LOG.info("Contact removed");
			removeItem(((ContactRemovedEvent) e).getContactId());
		} else if (e instanceof PrivateMessageReceivedEvent) {
			LOG.info("Private message received, reloading count");
			PrivateMessageReceivedEvent p = (PrivateMessageReceivedEvent) e;
			reloadGroupCount(p.getContactId());
		} else if (e instanceof IntroductionRequestReceivedEvent) {
			LOG.info("Introduction request received, reloading count");
			IntroductionRequestReceivedEvent i =
					(IntroductionRequestReceivedEvent) e;
			reloadGroupCount(i.getContactId());
		} else if (e instanceof IntroductionResponseReceivedEvent) {
			LOG.info("Introduction response received, reloading count");
			IntroductionResponseReceivedEvent i =
					(IntroductionResponseReceivedEvent) e;
			reloadGroupCount(i.getContactId());
		} else if (e instanceof InvitationRequestReceivedEvent) {
			LOG.info("Invitation request received, reloading count");
			InvitationRequestReceivedEvent i =
					(InvitationRequestReceivedEvent) e;
			reloadGroupCount(i.getContactId());
		} else if (e instanceof InvitationResponseReceivedEvent) {
			LOG.info("Invitation response received, reloading count");
			InvitationResponseReceivedEvent i =
					(InvitationResponseReceivedEvent) e;
			reloadGroupCount(i.getContactId());
		}
	}

	private void reloadGroupCount(final ContactId c) {
		listener.runOnDbThread(new Runnable() {
			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					GroupCount count = conversationManager.getGroupCount(c);
					long duration = System.currentTimeMillis() - now;
					if (LOG.isLoggable(INFO))
						LOG.info("Reloading count took " + duration + " ms");
					updateItem(c, count);
				} catch (NoSuchContactException e) {
					// We'll remove the item when we get the event
				} catch (DbException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		});
	}

	private void updateItem(final ContactId c, final GroupCount count) {
		listener.runOnUiThreadUnlessDestroyed(new Runnable() {
			@Override
			public void run() {
				int position = adapter.findItemPosition(c);
				ContactListItem item = adapter.getItemAt(position);
				if (item != null) {
					item.setGroupCount(count);
					adapter.updateItemAt(position, item);
				}
			}
		});
	}

	private void removeItem(final ContactId c) {
		listener.runOnDbThread(new Runnable() {
			@Override
			public void run() {
				listener.runOnUiThreadUnlessDestroyed(new Runnable() {
					@Override
					public void run() {
						int position = adapter.findItemPosition(c);
						ContactListItem item = adapter.getItemAt(position);
						if (item != null) adapter.remove(item);
					}
				});
			}
		});
	}

	private void setConnected(final ContactId c, final boolean connected) {
		// Update via the background executor to avoid races
		listener.runOnDbThread(new Runnable() {
			@Override
			public void run() {
				listener.runOnUiThreadUnlessDestroyed(new Runnable() {
					@Override
					public void run() {
						int position = adapter.findItemPosition(c);
						ContactListItem item = adapter.getItemAt(position);
						if (item != null) {
							item.setConnected(connected);
							adapter.notifyItemChanged(position);
						}
					}
				});
			}
		});
	}

}
