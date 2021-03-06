package scray.cassandra.automation

import com.typesafe.scalalogging.slf4j.LazyLogging
import com.websudos.phantom.CassandraPrimitive
import scray.cassandra.rows.GenericCassandraRowStoreMapper
import scray.querying.description.{ Row, TableIdentifier, VersioningConfiguration }
import scray.querying.storeabstraction.StoreGenerators
import scray.cassandra.sync.CassandraDbSession
import scray.querying.sync.DbSession
import scray.querying.storeabstraction.StoreExtractor
import scray.cassandra.extractors.CassandraExtractor
import scray.querying.source.store.QueryableStoreSource
import scray.querying.queries.DomainQuery
import com.twitter.util.FuturePool
import scray.cassandra.CassandraQueryableSource

/**
 * Generators for Scray store abstractions for Cassandra
 */
class CassandraStoreGenerators(dbID: String, session: DbSession[_, _, _], 
    cassSessionHandler: CassandraSessionHandler, futurePool: FuturePool)(
        implicit typeMaps: Map[String, CassandraPrimitive[_]]) extends StoreGenerators with LazyLogging {
  
  lazy val casssession = session match {
    case cassandra: CassandraDbSession => cassandra 
    case _ => // this should never happen...
        throw new RuntimeException("Store session is not a Cassandra Session. This is a bug. Please report.")  
  }
  
  override def createRowStore[Q <: DomainQuery](table: TableIdentifier): 
      Option[(QueryableStoreSource[Q], ((_) => Row, Option[String], Option[VersioningConfiguration[_, _]]))] = {
    val TableIdentifier(db, keyspace, cfname) = table 
    if (db == dbID) {
      val casscluster = casssession.cassandraSession.getCluster
      val resolvedsession = cassSessionHandler.getSession((dbID, keyspace), casscluster)
      val store = RowStoreFactory.getRowStore(table, session)(typeMaps, futurePool)._1.get
      Some((store, (GenericCassandraRowStoreMapper.cassandraRowToScrayRowMapper(store), None, None)))
    } else {
      None
    }}.asInstanceOf[Option[(QueryableStoreSource[Q], (_ => Row, Option[String], Option[VersioningConfiguration[_, _]]))]]

  override def getExtractor[Q <: DomainQuery, S <: QueryableStoreSource[Q]](
      store: S, tableName: Option[String], versions: Option[VersioningConfiguration[_, _]], 
      dbSystem: Option[String], futurePool: FuturePool): StoreExtractor[S] = {
    store match {
      case cassStore: CassandraQueryableSource[q] => 
        CassandraExtractor.getExtractor(cassStore, tableName, versions, dbSystem, futurePool).asInstanceOf[StoreExtractor[S]]
      case _ => throw new UnsupportedOperationException("CassandraStoreGenerators can only be used with Cassandra stores")
    }
  }  
}