package org.mobicents.cluster.infinispan;

import java.util.List;

import org.mobicents.cluster.ClusterNodeAddress;
import org.mobicents.cluster.FailoverElector;

/**
 * Infinispan simple impl of {@link FailoverElector}.
 * 
 * @author martins
 * 
 */
public class InfinispanFailOverElector implements FailoverElector {

	private final int shift = 5; // lets set default to something other than
									// zero

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.mobicents.cluster.FailoverElector#elect(java.util.List)
	 */
	@Override
	public ClusterNodeAddress elect(List<ClusterNodeAddress> list) {
		// Jgroups return addresses always in sorted order, infinispan does not
		// change it.
		int size = list.size();
		int index = (this.shift % size) + size;
		index = index % size;
		return list.get(index);
	}

}