package scray.cassandra.sync.helpers

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import com.datastax.driver.core.Cluster
import com.datastax.driver.core.ResultSet
import com.datastax.driver.core.Statement
import com.datastax.driver.core.querybuilder.Insert
import scray.querying.sync._
import scray.cassandra.sync.CassandraImplementation._
import scray.querying.sync.ArbitrarylyTypedRows
import scray.querying.sync.Column
import scray.querying.sync.DbSession
import shapeless.ops.hlist._
import shapeless.syntax.singleton._
import com.typesafe.scalalogging.slf4j.LazyLogging


  /**
   * Test columns
   */
  class SumTestColumns() extends ArbitrarylyTypedRows {
    val sum = new Column[Long]("sum")

    override val columns = sum :: Nil
    override val primaryKey = s"(${sum.name})"
    override val indexes: Option[List[String]] = None
  }

  class TestDbSession extends DbSession[Statement, Insert, ResultSet]("127.0.0.1") with LazyLogging {
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(EmbeddedCassandraServerHelper.CASSANDRA_RNDPORT_YML_FILE)
    
    var cassandraSession = Cluster.builder().addContactPoint("127.0.0.1").withPort(EmbeddedCassandraServerHelper.getNativeTransportPort).build().connect()

    override def execute(statement: String): Try[ResultSet] = {
      val result = cassandraSession.execute(statement)
      if (result.wasApplied()) {
        Success(result)
      } else {
        Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
      }
    }

    def execute(statement: Statement): Try[ResultSet] = {
      logger.debug("Execute: " +  statement)

      val result = cassandraSession.execute(statement)
      if (result.wasApplied()) {
        Success(result)
      } else {
        Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
      }
    }

    def insert(statement: Insert): Try[ResultSet] = {
      logger.debug("Execute: " +  statement)
      val result = cassandraSession.execute(statement)
      if (result.wasApplied()) {
        Success(result)
      } else {
        Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
      }
    }
    
    def cleanDb = ???
  }