/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.TransactionManager;

import org.apache.log4j.Logger;
import org.jboss.cache.Cache;
import org.jboss.cache.Fqn;
import org.jboss.cache.Node;
import org.jboss.cache.config.Configuration.CacheMode;
import org.jboss.cache.notifications.annotation.BuddyGroupChanged;
import org.jboss.cache.notifications.annotation.CacheListener;
import org.jboss.cache.notifications.annotation.NodeRemoved;
import org.jboss.cache.notifications.annotation.ViewChanged;
import org.jboss.cache.notifications.event.BuddyGroupChangedEvent;
import org.jboss.cache.notifications.event.NodeRemovedEvent;
import org.jboss.cache.notifications.event.ViewChangedEvent;
import org.jgroups.Address;
import org.mobicents.cache.MobicentsCache;
import org.mobicents.cluster.cache.ClusteredCacheData;
import org.mobicents.cluster.cache.ClusteredCacheDataIndexingHandler;
import org.mobicents.cluster.cache.DefaultClusteredCacheDataIndexingHandler;
import org.mobicents.cluster.election.ClientLocalListenerElector;
import org.mobicents.cluster.election.ClusterElector;

/**
 * Listener that is to be used for cluster wide replication(meaning no buddy
 * replication, no data gravitation). It will index activity on nodes marking
 * current node as owner(this is semi-gravitation behavior (we don't delete, we
 * just mark)). 
 * 
 * Indexing is only at node level, i.e., there is no
 * reverse indexing, so it has to iterate through whole resource group data FQNs to check which
 * nodes should be taken over.
 * 
 * @author <a href="mailto:baranowb@gmail.com">Bartosz Baranowski </a>
 * @author martins
 */

@CacheListener(sync = false)
public class DefaultMobicentsCluster implements MobicentsCluster {

	private static final String FQN_SEPARATOR = "/";

	private static final String BUDDY_BACKUP_FQN_ROOT = "/_BUDDY_BACKUP_/";

	private static final Logger logger = Logger.getLogger(DefaultMobicentsCluster.class);

	private final SortedSet<FailOverListener> failOverListeners;
	@SuppressWarnings("unchecked")
	private final ConcurrentHashMap<Fqn, DataRemovalListener> dataRemovalListeners;
	
	private final MobicentsCache mobicentsCache;
	private final TransactionManager txMgr;
	private final ClusterElector elector;
	private final DefaultClusteredCacheDataIndexingHandler clusteredCacheDataIndexingHandler;
	
	private List<Address> currentView;
	
	@SuppressWarnings("unchecked")
	public DefaultMobicentsCluster(MobicentsCache watchedCache, TransactionManager txMgr, ClusterElector elector) {
		this.failOverListeners = Collections.synchronizedSortedSet(new TreeSet<FailOverListener>(new FailOverListenerPriorityComparator()));
		this.dataRemovalListeners = new ConcurrentHashMap<Fqn, DataRemovalListener>();
		this.mobicentsCache = watchedCache;
		final Cache<?,?> cache = watchedCache.getJBossCache();
		if (!cache.getConfiguration().getCacheMode().equals(CacheMode.LOCAL)) {
			// get current cluster members
			currentView = new ArrayList<Address>(cache.getConfiguration().getRuntimeConfig().getChannel().getView().getMembers());
			// start listening to events
			cache.addCacheListener(this);		
		}
		this.txMgr = txMgr;
		this.elector = elector;
		this.clusteredCacheDataIndexingHandler = new DefaultClusteredCacheDataIndexingHandler();
	}

	/* (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#getLocalAddress()
	 */
	public Address getLocalAddress() {
		return mobicentsCache.getJBossCache().getLocalAddress();
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#getClusterMembers()
	 */
	public List<Address> getClusterMembers() {
		if (currentView != null) {
			return Collections.unmodifiableList(currentView);
		}
		else {
			final Address localAddress = getLocalAddress();
			if (localAddress == null) {
				return Collections.emptyList();
			}
			else {
				final List<Address> list = new ArrayList<Address>();
				list.add(localAddress);
				return Collections.unmodifiableList(list);
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#isHeadMember()
	 */
	public boolean isHeadMember() {
		final Address localAddress = getLocalAddress();
		if (localAddress != null) {
			final List<Address> clusterMembers = getClusterMembers();
			return !clusterMembers.isEmpty() && clusterMembers.get(0).equals(localAddress);
		}
		else {
			return true;
		}
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#isSingleMember()
	 */
	public boolean isSingleMember() {
		final Address localAddress = getLocalAddress();
		if (localAddress != null) {
			final List<Address> clusterMembers = getClusterMembers();
			return clusterMembers.size() == 1;
		}
		else {
			return true;
		}
	}
	
	/**
	 * Method handle a change on the cluster members set
	 * @param event
	 */
	@ViewChanged
	public synchronized void onViewChangeEvent(ViewChangedEvent event) {
		
		if (logger.isDebugEnabled()) {
			logger.debug("onViewChangeEvent : pre[" + event.isPre() + "] : event local address[" + event.getCache().getLocalAddress() + "]");
		}
				
		final List<Address> oldView = currentView;
		currentView = new ArrayList<Address>(event.getNewView().getMembers());
		final Address localAddress = getLocalAddress();
		
		// recover stuff from lost members
		Runnable runnable = new Runnable() {
			public void run() {
				for (Address oldMember : oldView) {
					if (!currentView.contains(oldMember)) {
						if (logger.isDebugEnabled()) {
							logger.debug("onViewChangeEvent : processing lost member " + oldMember);
						}				
						for (FailOverListener localListener : failOverListeners) {
							boolean electionDone = false;
							boolean elected = false;
							ClientLocalListenerElector localListenerElector = localListener.getElector();
							if (localListenerElector != null) {
								// going to use the local listener elector instead, which gives results based on data
								performTakeOver(localListener,oldMember,localAddress,true);
							}
							else {
								if (!electionDone) {
									electionDone = true;
									elected = elector.elect(currentView).equals(localAddress);
								}
								if (elected) {
									// local member was elected to take over the work being done by the lost member
									performTakeOver(localListener,oldMember,localAddress,false);
								}
							}
						}																	
					}
				}
			}
		};
		Thread t = new Thread(runnable);
		t.start();
		
	}

	/**
	 * 
	 */
	@SuppressWarnings("unchecked")
	private void performTakeOver(FailOverListener localListener, Address lostMember, Address localAddress, boolean useLocalListenerElector) {
	
		if (logger.isDebugEnabled()) {
			logger.debug("onViewChangeEvent : failing over lost member " + lostMember);
		}
		
		final Cache jbossCache = mobicentsCache.getJBossCache();
					
			final Fqn rootFqnOfChanges = localListener.getBaseFqn();
			
			boolean createdTx = false;
			boolean doRollback = true;
			
			try {
				if (txMgr != null && txMgr.getTransaction() == null) {
					txMgr.begin();
					createdTx = true;
				}
				if(jbossCache.getConfiguration().getBuddyReplicationConfig().isEnabled()) {     
					// replace column to underscore in the couple ipaddress:port of the jgroups address
					// to match the BUDDY GROUP Fqn pattern in the cache
					String lostMemberFqnizied = lostMember.toString().replace(":", "_");
					String fqn = BUDDY_BACKUP_FQN_ROOT + lostMemberFqnizied  + localListener.getBaseFqn();					
					
					Node buddyGroupRootNode = jbossCache.getNode(Fqn.fromString(fqn));					
					Set<Node> children = buddyGroupRootNode.getChildren();
					if(logger.isDebugEnabled()) {
						logger.debug("Fqn : " + fqn + " : children " + children);						
					}
					//force data gravitation for each node under the base fqn we want to retrieve from the buddy that died
					for(Node child : children) {
						Fqn childFqn = Fqn.fromString(localListener.getBaseFqn().toString() + FQN_SEPARATOR + child.getFqn().getLastElementAsString());
						if(logger.isDebugEnabled()) {
							logger.debug("forcing data gravitation on following child fqn " +  childFqn);
						}
						jbossCache.getInvocationContext().getOptionOverrides().setForceDataGravitation(true);
						jbossCache.getNode(childFqn);
					}
				}
				if (createdTx) {
					txMgr.commit();
					createdTx = false;
				}
				
				if (txMgr != null && txMgr.getTransaction() == null) {
					txMgr.begin();
					createdTx = true;
				}
											
				localListener.failOverClusterMember(lostMember);
				
				for (Object childName : jbossCache.getChildrenNames(rootFqnOfChanges)) {
					// Here in values we store data and... inet node., we must match
					// passed one.
					final ClusteredCacheData clusteredCacheData = new ClusteredCacheData(Fqn.fromRelativeElements(rootFqnOfChanges, childName),this);
					if (clusteredCacheData.exists()) {
						Address address = clusteredCacheData.getClusterNodeAddress();
						if (address != null && address.equals(lostMember)) {
							// may need to do election using client local listener
							if (useLocalListenerElector) {
								if(!localAddress.equals(localListener.getElector().elect(currentView, clusteredCacheData))) {
									// not elected, move on
									continue;
								}
							}
							// call back the listener
							localListener.wonOwnership(clusteredCacheData);
							// change ownership
							clusteredCacheData.setClusterNodeAddress(localAddress);							
						}					
					}else
					{
						//FIXME: debug?
						if(logger.isDebugEnabled())
						{
							logger.debug(" Attempt to index: "+Fqn.fromRelativeElements(rootFqnOfChanges, childName)+" failed, node does not exist.");
						}
					}
				}
				doRollback = false;
				
			} catch (Exception e) {
				logger.error(e.getMessage(),e);
				
			} finally {
				if (createdTx) {					
					try {
						if (!doRollback) {
							txMgr.commit();
						}
						else {
							txMgr.rollback();
						}
					} catch (Exception e) {
						logger.error(e.getMessage(),e);
					}
				}
			}

	}

	@NodeRemoved
	public void onNodeRemovedEvent(NodeRemovedEvent event) {
		if(!event.isOriginLocal()) {			
			final DataRemovalListener dataRemovalListener = dataRemovalListeners.get(event.getFqn().getParent());
			if (dataRemovalListener != null) {
				dataRemovalListener.dataRemoved(event.getFqn());
			}
		}
	}
	
	// NOTE USED FOR NOW
	
	/*
	@NodeCreated
	public void onNodeCreateddEvent(NodeCreatedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeCreateddEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] : event local address[" + event.getCache().getLocalAddress()
					+ "]");
		}
	}

	@NodeModified
	public void onNodeModifiedEvent(NodeModifiedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeModifiedEvent : pre[" + event.isPre() + "] : event local address[" + event.getCache().getLocalAddress() + "]");
		}
	}

	@NodeMoved
	public void onNodeMovedEvent(NodeMovedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeMovedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodeVisited
	public void onNodeVisitedEvent(NodeVisitedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeVisitedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodeLoaded
	public void onNodeLoadedEvent(NodeLoadedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeLoadedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodeEvicted
	public void onNodeEvictedEvent(NodeEvictedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeEvictedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodeInvalidated
	public void onNodeInvalidatedEvent(NodeInvalidatedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodeInvalidatedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodeActivated
	public void onNodeActivatedEvent(NodeActivatedEvent event) {

		if (log.isDebugEnabled()) {
			log.debug("onNodeActivatedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@NodePassivated
	public void onNodePassivatedEvent(NodePassivatedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onNodePassivatedEvent : " + event.getFqn() + " : local[" + event.isOriginLocal() + "] pre[" + event.isPre() + "] ");
		}
	}

	@BuddyGroupChanged
	public void onBuddyGroupChangedEvent(BuddyGroupChangedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onBuddyGroupChangedEvent : pre[" + event.isPre() + "] ");
		}
	}

	@CacheStarted
	public void onCacheStartedEvent(CacheStartedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onCacheStartedEvent : pre[" + event.isPre() + "] ");
		}
	}

	@CacheStopped
	public void onCacheStoppedEvent(CacheStoppedEvent event) {
		if (log.isDebugEnabled()) {
			log.debug("onCacheStoppedEvent : pre[" + event.isPre() + "] ");
		}
	}
	*/

	// LOCAL LISTENERS MANAGEMENT
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#addFailOverListener(org.mobicents.cluster.FailOverListener)
	 */
	public boolean addFailOverListener(FailOverListener localListener) {
		if (logger.isDebugEnabled()) {
			logger.debug("Adding local listener " + localListener);
		}
		for(FailOverListener failOverListener : failOverListeners) {
			if (failOverListener.getBaseFqn().equals(localListener.getBaseFqn())) {
				return false; 
			}
		}
		return failOverListeners.add(localListener);		
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#removeFailOverListener(org.mobicents.cluster.FailOverListener)
	 */
	public boolean removeFailOverListener(FailOverListener localListener) {
		if (logger.isDebugEnabled()) {
			logger.debug("Removing local listener " + localListener);
		}
		return failOverListeners.remove(localListener);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#addDataRemovalListener(org.mobicents.cluster.DataRemovalListener)
	 */
	public boolean addDataRemovalListener(DataRemovalListener listener) {
		return dataRemovalListeners.putIfAbsent(listener.getBaseFqn(), listener) == null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#removeDataRemovalListener(org.mobicents.cluster.DataRemovalListener)
	 */
	public boolean removeDataRemovalListener(DataRemovalListener listener) {
		return dataRemovalListeners.remove(listener.getBaseFqn()) != null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#getMobicentsCache()
	 */
	public MobicentsCache getMobicentsCache() {
		return mobicentsCache;
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.cluster.MobicentsCluster#getClusteredCacheDataIndexingHandler()
	 */
	public ClusteredCacheDataIndexingHandler getClusteredCacheDataIndexingHandler() {
		return clusteredCacheDataIndexingHandler;
	}
}
