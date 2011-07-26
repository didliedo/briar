package net.sf.briar.db;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.briar.api.ContactId;
import net.sf.briar.api.Rating;
import net.sf.briar.api.db.DbException;
import net.sf.briar.api.db.NoSuchContactException;
import net.sf.briar.api.protocol.Ack;
import net.sf.briar.api.protocol.AuthorId;
import net.sf.briar.api.protocol.Batch;
import net.sf.briar.api.protocol.BatchId;
import net.sf.briar.api.protocol.Group;
import net.sf.briar.api.protocol.GroupId;
import net.sf.briar.api.protocol.Message;
import net.sf.briar.api.protocol.MessageId;
import net.sf.briar.api.protocol.Offer;
import net.sf.briar.api.protocol.Subscriptions;
import net.sf.briar.api.protocol.Transports;
import net.sf.briar.api.protocol.writers.AckWriter;
import net.sf.briar.api.protocol.writers.BatchWriter;
import net.sf.briar.api.protocol.writers.OfferWriter;
import net.sf.briar.api.protocol.writers.RequestWriter;
import net.sf.briar.api.protocol.writers.SubscriptionWriter;
import net.sf.briar.api.protocol.writers.TransportWriter;

import com.google.inject.Inject;

/**
 * An implementation of DatabaseComponent using Java synchronization. This
 * implementation does not distinguish between readers and writers.
 */
class SynchronizedDatabaseComponent<Txn> extends DatabaseComponentImpl<Txn> {

	private static final Logger LOG =
		Logger.getLogger(SynchronizedDatabaseComponent.class.getName());

	/*
	 * Locks must always be acquired in alphabetical order. See the Database
	 * interface to find out which calls require which locks.
	 */

	private final Object contactLock = new Object();
	private final Object messageLock = new Object();
	private final Object messageStatusLock = new Object();
	private final Object ratingLock = new Object();
	private final Object subscriptionLock = new Object();
	private final Object transportLock = new Object();

	@Inject
	SynchronizedDatabaseComponent(Database<Txn> db, DatabaseCleaner cleaner) {
		super(db, cleaner);
	}

	protected void expireMessages(int size) throws DbException {
		synchronized(contactLock) {
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Txn txn = db.startTransaction();
					try {
						for(MessageId m : db.getOldMessages(txn, size)) {
							removeMessage(txn, m);
						}
						db.commitTransaction(txn);
					} catch(DbException e) {
						db.abortTransaction(txn);
						throw e;
					}
				}
			}
		}
	}

	public void close() throws DbException {
		cleaner.stopCleaning();
		synchronized(contactLock) {
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					synchronized(ratingLock) {
						synchronized(subscriptionLock) {
							synchronized(transportLock) {
								db.close();
							}
						}
					}
				}
			}
		}
	}

	public ContactId addContact(Map<String, String> transports)
	throws DbException {
		if(LOG.isLoggable(Level.FINE)) LOG.fine("Adding contact");
		synchronized(contactLock) {
			synchronized(transportLock) {
				Txn txn = db.startTransaction();
				try {
					ContactId c = db.addContact(txn, transports);
					db.commitTransaction(txn);
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Added contact " + c);
					return c;
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void addLocallyGeneratedMessage(Message m) throws DbException {
		waitForPermissionToWrite();
		synchronized(contactLock) {
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					synchronized(subscriptionLock) {
						Txn txn = db.startTransaction();
						try {
							// Don't store the message if the user has
							// unsubscribed from the group
							if(db.containsSubscription(txn, m.getGroup())) {
								boolean added = storeMessage(txn, m, null);
								if(!added) {
									if(LOG.isLoggable(Level.FINE))
										LOG.fine("Duplicate local message");
								}
							} else {
								if(LOG.isLoggable(Level.FINE))
									LOG.fine("Not subscribed");
							}
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
				}
			}
		}
	}

	public void findLostBatches(ContactId c) throws DbException {
		// Find any lost batches that need to be retransmitted
		Collection<BatchId> lost;
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Txn txn = db.startTransaction();
					try {
						lost = db.getLostBatches(txn, c);
						db.commitTransaction(txn);
					} catch(DbException e) {
						db.abortTransaction(txn);
						throw e;
					}
				}
			}
		}
		for(BatchId batch : lost) {
			synchronized(contactLock) {
				if(!containsContact(c)) throw new NoSuchContactException();
				synchronized(messageLock) {
					synchronized(messageStatusLock) {
						Txn txn = db.startTransaction();
						try {
							if(LOG.isLoggable(Level.FINE))
								LOG.fine("Removing lost batch");
							db.removeLostBatch(txn, c, batch);
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
				}
			}
		}
	}

	public void generateAck(ContactId c, AckWriter a) throws DbException,
	IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageStatusLock) {
				Txn txn = db.startTransaction();
				try {
					Collection<BatchId> acks = db.getBatchesToAck(txn, c);
					Collection<BatchId> sent = new ArrayList<BatchId>();
					for(BatchId b : acks) if(a.writeBatchId(b)) sent.add(b);
					a.finish();
					db.removeBatchesToAck(txn, c, sent);
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Added " + acks.size() + " acks");
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				} catch(IOException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void generateBatch(ContactId c, BatchWriter b) throws DbException,
	IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Txn txn = db.startTransaction();
					try {
						int capacity = b.getCapacity();
						Iterator<MessageId> it =
							db.getSendableMessages(txn, c, capacity).iterator();
						Collection<MessageId> sent = new ArrayList<MessageId>();
						int bytesSent = 0;
						while(it.hasNext()) {
							MessageId m = it.next();
							byte[] message = db.getMessage(txn, m);
							if(!b.writeMessage(message)) break;
							bytesSent += message.length;
							sent.add(m);
						}
						BatchId id = b.finish();
						// Record the contents of the batch, unless it's empty
						if(!sent.isEmpty())
							db.addOutstandingBatch(txn, c, id, sent);
						db.commitTransaction(txn);
					} catch(DbException e) {
						db.abortTransaction(txn);
						throw e;
					} catch(IOException e) {
						db.abortTransaction(txn);
						throw e;
					}
				}
			}
		}
	}

	public Collection<MessageId> generateBatch(ContactId c, BatchWriter b,
			Collection<MessageId> requested) throws DbException, IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Txn txn = db.startTransaction();
					try {
						Collection<MessageId> sent = new ArrayList<MessageId>();
						int bytesSent = 0;
						for(MessageId m : requested) {
							byte[] message = db.getMessageIfSendable(txn, c, m);
							if(message == null) continue;
							if(!b.writeMessage(message)) break;
							bytesSent += message.length;
							sent.add(m);
						}
						BatchId id = b.finish();
						// Record the contents of the batch, unless it's empty
						if(!sent.isEmpty())
							db.addOutstandingBatch(txn, c, id, sent);
						db.commitTransaction(txn);
						return sent;
					} catch(DbException e) {
						db.abortTransaction(txn);
						throw e;
					} catch(IOException e) {
						db.abortTransaction(txn);
						throw e;
					}
				}
			}
		}
	}

	public Collection<MessageId> generateOffer(ContactId c, OfferWriter o)
	throws DbException, IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Txn txn = db.startTransaction();
					try {
						Collection<MessageId> sendable =
							db.getSendableMessages(txn, c, Integer.MAX_VALUE);
						Iterator<MessageId> it = sendable.iterator();
						Collection<MessageId> sent = new ArrayList<MessageId>();
						while(it.hasNext()) {
							MessageId m = it.next();
							if(!o.writeMessageId(m)) break;
							sent.add(m);
						}
						o.finish();
						db.commitTransaction(txn);
						return sent;
					} catch(DbException e) {
						db.abortTransaction(txn);
						throw e;
					} catch(IOException e) {
						db.abortTransaction(txn);
						throw e;
					}
				}
			}
		}
	}

	public void generateSubscriptions(ContactId c, SubscriptionWriter s)
	throws DbException, IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(subscriptionLock) {
				Txn txn = db.startTransaction();
				try {
					Collection<Group> subs = db.getSubscriptions(txn);
					s.writeSubscriptions(subs);
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Added " + subs.size() + " subscriptions");
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				} catch(IOException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void generateTransports(ContactId c, TransportWriter t)
	throws DbException, IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(transportLock) {
				Txn txn = db.startTransaction();
				try {
					Map<String, String> transports = db.getTransports(txn);
					t.writeTransports(transports);
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Added " + transports.size() + " transports");
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				} catch(IOException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public Collection<ContactId> getContacts() throws DbException {
		synchronized(contactLock) {
			Txn txn = db.startTransaction();
			try {
				Collection<ContactId> contacts = db.getContacts(txn);
				db.commitTransaction(txn);
				return contacts;
			} catch(DbException e) {
				db.abortTransaction(txn);
				throw e;
			}
		}
	}

	public Rating getRating(AuthorId a) throws DbException {
		synchronized(ratingLock) {
			Txn txn = db.startTransaction();
			try {
				Rating r = db.getRating(txn, a);
				db.commitTransaction(txn);
				return r;
			} catch(DbException e) {
				db.abortTransaction(txn);
				throw e;
			}
		}
	}

	public Collection<Group> getSubscriptions() throws DbException {
		synchronized(subscriptionLock) {
			Txn txn = db.startTransaction();
			try {
				Collection<Group> subs = db.getSubscriptions(txn);
				db.commitTransaction(txn);
				return subs;
			} catch(DbException e) {
				db.abortTransaction(txn);
				throw e;
			}
		}
	}

	public Map<String, String> getTransports() throws DbException {
		synchronized(transportLock) {
			Txn txn = db.startTransaction();
			try {
				Map<String, String> transports = db.getTransports(txn);
				db.commitTransaction(txn);
				return transports;
			} catch(DbException e) {
				db.abortTransaction(txn);
				throw e;
			}
		}
	}

	public Map<String, String> getTransports(ContactId c) throws DbException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(transportLock) {
				Txn txn = db.startTransaction();
				try {
					Map<String, String> transports = db.getTransports(txn, c);
					db.commitTransaction(txn);
					return transports;
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void receiveAck(ContactId c, Ack a) throws DbException {
		// Mark all messages in acked batches as seen
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					Collection<BatchId> acks = a.getBatches();
					for(BatchId ack : acks) {
						Txn txn = db.startTransaction();
						try {
							db.removeAckedBatch(txn, c, ack);
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Received " + acks.size() + " acks");
				}
			}
		}
	}

	public void receiveBatch(ContactId c, Batch b) throws DbException {
		waitForPermissionToWrite();
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					synchronized(subscriptionLock) {
						Txn txn = db.startTransaction();
						try {
							int received = 0, stored = 0;
							for(Message m : b.getMessages()) {
								received++;
								GroupId g = m.getGroup();
								if(db.containsSubscription(txn, g)) {
									if(storeMessage(txn, m, c)) stored++;
								}
							}
							if(LOG.isLoggable(Level.FINE))
								LOG.fine("Received " + received
										+ " messages, stored " + stored);
							db.addBatchToAck(txn, c, b.getId());
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
				}
			}
		}
	}

	public void receiveOffer(ContactId c, Offer o, RequestWriter r)
	throws DbException, IOException {
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					synchronized(subscriptionLock) {
						BitSet request;
						Txn txn = db.startTransaction();
						try {
							Collection<MessageId> offered = o.getMessages();
							request = new BitSet(offered.size());
							Iterator<MessageId> it = offered.iterator();
							for(int i = 0; it.hasNext(); i++) {
								// If the message is not in the database, or if
								// it is not visible to the contact, request it
								MessageId m = it.next();
								if(!db.setStatusSeenIfVisible(txn, c, m))
									request.set(i);
							}
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
						r.writeBitmap(request);
					}
				}
			}
		}
	}

	public void receiveSubscriptions(ContactId c, Subscriptions s)
	throws DbException {
		// Update the contact's subscriptions
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(subscriptionLock) {
				Txn txn = db.startTransaction();
				try {
					Collection<Group> subs = s.getSubscriptions();
					db.setSubscriptions(txn, c, subs, s.getTimestamp());
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Received " + subs.size() + " subscriptions");
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void receiveTransports(ContactId c, Transports t)
	throws DbException {
		// Update the contact's transport details
		synchronized(contactLock) {
			if(!containsContact(c)) throw new NoSuchContactException();
			synchronized(transportLock) {
				Txn txn = db.startTransaction();
				try {
					Map<String, String> transports = t.getTransports();
					db.setTransports(txn, c, transports, t.getTimestamp());
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Received " + transports.size()
								+ " transports");
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void removeContact(ContactId c) throws DbException {
		if(LOG.isLoggable(Level.FINE)) LOG.fine("Removing contact " + c);
		synchronized(contactLock) {
			synchronized(messageStatusLock) {
				synchronized(subscriptionLock) {
					synchronized(transportLock) {
						Txn txn = db.startTransaction();
						try {
							db.removeContact(txn, c);
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
				}
			}
		}
	}

	public void setRating(AuthorId a, Rating r) throws DbException {
		synchronized(messageLock) {
			synchronized(ratingLock) {
				Txn txn = db.startTransaction();
				try {
					Rating old = db.setRating(txn, a, r);
					// Update the sendability of the author's messages
					if(r == Rating.GOOD && old != Rating.GOOD)
						updateAuthorSendability(txn, a, true);
					else if(r != Rating.GOOD && old == Rating.GOOD)
						updateAuthorSendability(txn, a, false);
					db.commitTransaction(txn);
				} catch(DbException e) {
					db.abortTransaction(txn);
					throw e;
				}
			}
		}
	}

	public void subscribe(Group g) throws DbException {
		if(LOG.isLoggable(Level.FINE)) LOG.fine("Subscribing to " + g);
		synchronized(subscriptionLock) {
			Txn txn = db.startTransaction();
			try {
				db.addSubscription(txn, g);
				db.commitTransaction(txn);
			} catch(DbException e) {
				db.abortTransaction(txn);
				throw e;
			}
		}
	}

	public void unsubscribe(GroupId g) throws DbException {
		if(LOG.isLoggable(Level.FINE)) LOG.fine("Unsubscribing from " + g);
		synchronized(contactLock) {
			synchronized(messageLock) {
				synchronized(messageStatusLock) {
					synchronized(subscriptionLock) {
						Txn txn = db.startTransaction();
						try {
							db.removeSubscription(txn, g);
							db.commitTransaction(txn);
						} catch(DbException e) {
							db.abortTransaction(txn);
							throw e;
						}
					}
				}
			}
		}
	}
}
