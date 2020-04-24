package org.infinispan.distribution.ch;

import static org.testng.AssertJUnit.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.infinispan.commons.hash.MurmurHash3;
import org.infinispan.distribution.ch.impl.DefaultConsistentHash;
import org.infinispan.distribution.ch.impl.OwnershipStatistics;
import org.infinispan.distribution.ch.impl.TopologyAwareSyncConsistentHashFactory;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.jgroups.JGroupsTopologyAwareAddress;
import org.jgroups.util.ExtendedUUID;
import org.testng.annotations.Test;

/**
 * Tests the uniformity of the SyncConsistentHashFactory algorithm, which is very similar to the 5.1
 * default consistent hash algorithm virtual nodes.
 *
 * <p>This test assumes that key hashes are random and follow a uniform distribution  so a key has the same chance
 * to land on each one of the 2^31 positions on the hash wheel.
 *
 * <p>The output should stay pretty much the same between runs, so I added and example output here: vnodes_key_dist.txt.
 *
 * <p>Notes about the test output:
 * <ul>
 * <li>{@code P(p)} is the probability of proposition {@code p} being true
 * <li>In the "Primary" rows {@code mean == total_keys / num_nodes} (each key has only one primary owner),
 * but in the "Any owner" rows {@code mean == total_keys / num_nodes * num_owners} (each key is stored on
 * {@code num_owner} nodes).
 * </ul>
 * @author Dan Berindei
 * @since 5.2
 */
@Test(testName = "distribution.ch.TopologyAwareSyncConsistentHashFactoryKeyDistributionTest", groups = "profiling")
public class TopologyAwareSyncConsistentHashFactoryKeyDistributionTest extends SyncConsistentHashFactoryKeyDistributionTest {

   @Override
   protected DefaultConsistentHash createConsistentHash(int numSegments, int numOwners, List<Address> members) {
      MurmurHash3 hash = MurmurHash3.getInstance();
      ConsistentHashFactory<DefaultConsistentHash> chf = new TopologyAwareSyncConsistentHashFactory();
      DefaultConsistentHash ch = chf.create(numOwners, numSegments, members, null);
      return ch;
   }

   @Override
   protected List<Address> createAddresses(int numNodes) {
      ArrayList<Address> addresses = new ArrayList<Address>(numNodes);
      for (int i = 0; i < numNodes; i++) {
         // siteId: s0 or s1, rackId: null, machineId: m0 - mXX (each nodes)
         // ExtendedUUID topologyAddress = JGroupsTopologyAwareAddress.randomUUID(null, "s" + (i % 2), null, "m" + i);
         // siteId: s1, rackId: r0 or r1, machineId: m0 - mXX (each nodes)
         ExtendedUUID topologyAddress = JGroupsTopologyAwareAddress.randomUUID(null, "s1", "r" + (i % 2), "m" + i);
         addresses.add(new IndexedTopologyAwareJGroupsAddress(topologyAddress, i));
      }
      return addresses;
   }

   @Override
   public void testRebalanceDistribution() {
      for (int nn : NUM_NODES) {
         Map<String, Map<Integer, String>> metrics = new TreeMap<String, Map<Integer, String>>();
         for (int ns : NUM_SEGMENTS) {
            for (Map.Entry<String, String> entry : computeMetricsAfterRebalance(ns, NUM_OWNERS, nn).entrySet()) {
               String metricName = entry.getKey();
               String metricValue = entry.getValue();
               Map<Integer, String> metric = metrics.get(metricName);
               if (metric == null) {
                  metric = new HashMap<Integer, String>();
                  metrics.put(metricName, metric);
               }
               metric.put(ns, metricValue);
            }
         }

         printMetrics(nn, metrics);
      }
   }

   @Override
   protected Map<String, String> computeMetricsAfterRebalance(int numSegments, int numOwners, int numNodes) {
      List<Address> members = createAddresses(numNodes);
      Map<String, String> metrics = new HashMap<String, String>();
      long[] distribution = new long[LOOPS * numNodes];
      long[] distributionPrimary = new long[LOOPS * numNodes];
      double[] largestRatio = new double[LOOPS];
      int distIndex = 0;

      // MurmurHash3 hash = MurmurHash3.getInstance();
      ConsistentHashFactory<DefaultConsistentHash> chf = new TopologyAwareSyncConsistentHashFactory();
      DefaultConsistentHash ch = chf.create(numOwners, numSegments, members, null);

      // loop leave/join and rebalance
      for (int i = 0; i < LOOPS; i++) {
         // leave
         IndexedTopologyAwareJGroupsAddress leaver = (IndexedTopologyAwareJGroupsAddress)members.remove(0);
         int leaverNodeIndex = leaver.getNodeIndex();
         DefaultConsistentHash rebalancedCH = chf.updateMembers(ch, members, null);
         ch = chf.rebalance(rebalancedCH);
         // join
         Address joiner = createSingleAddresses(leaverNodeIndex, leaver); // leaver
         members.add(joiner);
         rebalancedCH = chf.updateMembers(ch, members, null);
         ch = chf.rebalance(rebalancedCH);
         // stats after rebalance
         OwnershipStatistics stats = new OwnershipStatistics(ch, ch.getMembers());
         assertEquals(numSegments * numOwners, stats.sumOwned());
         for (Address node : ch.getMembers()) {
            distribution[distIndex] = stats.getOwned(node);
            distributionPrimary[distIndex] = stats.getPrimaryOwned(node);
            distIndex++;
         }
         largestRatio[i] = getSegmentsPerNodesMinMaxRatio(ch);
      }
      Arrays.sort(distribution);
      Arrays.sort(distributionPrimary);
      Arrays.sort(largestRatio);

      addMetrics(metrics, "Any owner:", numSegments, numOwners, numNodes, distribution, INTERVALS);
      addMetrics(metrics, "Primary:", numSegments, 1, numNodes, distributionPrimary, INTERVALS_PRIMARY);
      addDoubleMetric(metrics, "Segments per node - max/min ratio", largestRatio[largestRatio.length -1]);
      return metrics;
   }

   @Override
   protected Address createSingleAddresses(int i) {
      return new IndexedTopologyAwareJGroupsAddress(ExtendedUUID.randomUUID(), i);
   }

   protected Address createSingleAddresses(int i, IndexedTopologyAwareJGroupsAddress address) {
      ExtendedUUID topologyAddress = JGroupsTopologyAwareAddress.randomUUID(null, address.getSiteId(), address.getRackId(), address.getMachineId());
      return new IndexedTopologyAwareJGroupsAddress(topologyAddress, i);
   }
}

/**
 * We extend JGroupsAddress to make mapping an address to a node easier.
 */
class IndexedTopologyAwareJGroupsAddress extends JGroupsTopologyAwareAddress {
   final int nodeIndex;

   public IndexedTopologyAwareJGroupsAddress(ExtendedUUID address, int nodeIndex) {
      super(address);
      this.nodeIndex = nodeIndex;
   }

   public int getNodeIndex() {
      return nodeIndex;
   }

   @Override
   public String toString() {
      return Integer.toString(nodeIndex);
   }
}
