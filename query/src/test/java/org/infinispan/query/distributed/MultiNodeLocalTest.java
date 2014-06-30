package org.infinispan.query.distributed;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.Index;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.test.Person;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.transaction.TransactionMode;
import org.testng.annotations.Test;

import java.io.IOException;

/**
 * Similar to MultiNodeDistributedTest, but using a local cache configuration both for
 * the indexed cache and for the storage of the index data.
 *
 * @author Anna Manukyan
 */
@Test(groups = "functional", testName = "query.distributed.MultiNodeLocalTest")
public class MultiNodeLocalTest extends MultiNodeDistributedTest {

   protected EmbeddedCacheManager createCacheManager() throws IOException {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder
            .clustering()
            .cacheMode(CacheMode.LOCAL)
            .indexing()
            .index(Index.ALL)
            .addProperty("hibernate.search.lucene_version", "LUCENE_CURRENT")
            .addProperty("default.exclusive_index_use", "false")
            .addProperty("default.indexmanager", "org.infinispan.query.indexmanager.InfinispanIndexManager");

      if(transactionsEnabled()) {
         builder.transaction().transactionMode(TransactionMode.TRANSACTIONAL);
      }
      EmbeddedCacheManager cacheManager = TestCacheManagerFactory.createCacheManager(builder);
      cacheManagers.add(cacheManager);
      Cache<String, Person> cache = cacheManager.getCache();
      caches.add(cache);

      return cacheManager;
   }

   public void testIndexingWorkDistribution() throws Exception {
      try {
         createCacheManager();
         assertIndexSize(0);
         storeOn(caches.get(0), "k1", new Person("K. Firt", "Is not a character from the matrix", 1));
         assertIndexSize(1);

         createCacheManager();
         storeOn(caches.get(1), "k2", new Person("K. Seycond", "Is a pilot", 1));
         assertIndexSize(1);

         killMasterNode();
         assertIndexSize(1);
      }
      finally {
         TestingUtil.killCacheManagers(cacheManagers);
      }
   }

}
