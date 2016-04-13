package scray.querying.sync

import scala.annotation.tailrec
import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import com.datastax.driver.core.ResultSet
import com.datastax.driver.core.SimpleStatement
import scray.querying.description.Row
import scray.querying.sync.types.DbSession
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import com.datastax.driver.core.Cluster
import org.scalatest.BeforeAndAfter
import scray.querying.sync.types.DataTable
import scray.querying.sync.types.Column
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.querybuilder.Insert
import com.datastax.driver.core.Statement
import scray.querying.sync.cassandra.OnlineBatchSyncCassandra
import scray.querying.sync.types.ArbitrarylyTypedRows
import scray.querying.sync.types.ColumnWithValue
import scray.querying.sync.cassandra.CassandraImplementation._
import scray.querying.sync.cassandra.OnlineBatchSyncCassandra
import scray.querying.sync.types.RowWithValue
import shapeless._
import syntax.singleton._
import scray.querying.sync.types.ColumnWithValue
import shapeless.ops.hlist._
import scray.querying.sync._
import org.scalatest.BeforeAndAfterAll
import java.util.logging.LogManager
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.bridge.SLF4JBridgeHandler
import scray.querying.sync.types.SyncTable
import scray.querying.sync.types.State
import scala.util.Try
import scray.querying.sync.cassandra.CassandraImplementation.RichBoolean
import scala.util.Failure
import scala.util.Success
import scray.common.serialization.BatchID


@RunWith(classOf[JUnitRunner])
class OnlineBatchSyncTests extends WordSpec with BeforeAndAfter with BeforeAndAfterAll {
  var dbconnection: DbSession[Statement, Insert, ResultSet] = null

  override def beforeAll() = {
    LogManager.getLogManager().reset();
  }
  
  /**
   * Test columns
   */
  class SumTestColumns() extends ArbitrarylyTypedRows {
    val sum = new Column[Long]("sum")

    override val columns = sum :: Nil
    override val primaryKey = s"(${sum.name})"
    override val indexes: Option[List[String]] = None
  }
  
  val batchId = new BatchID(1L, 1L)

  before {
    dbconnection = new DbSession[Statement, Insert, ResultSet]("127.0.0.1") {
      EmbeddedCassandraServerHelper.startEmbeddedCassandra(EmbeddedCassandraServerHelper.CASSANDRA_RNDPORT_YML_FILE)
      val cassandraSession = Cluster.builder().addContactPoint("127.0.0.1").withPort(EmbeddedCassandraServerHelper.getNativeTransportPort).build().connect()

      override def execute(statement: String): Try[ResultSet] = {
        val result = cassandraSession.execute(statement)
        if(result.wasApplied()) {
          Success(result)
        } else {
          Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
        }      
      }

      def execute(statement: Statement): Try[ResultSet] = {
        val result = cassandraSession.execute(statement)
        if(result.wasApplied()) {
          Success(result)
        } else {
          Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
        }      
      }

      def insert(statement: Insert): Try[ResultSet] = {
        val result = cassandraSession.execute(statement)
        if(result.wasApplied()) {
          Success(result)
        } else {
          Failure(new StatementExecutionError(s"It was not possible to execute statement: ${statement}. Error: ${result.getExecutionInfo}"))
        }
      }
    }
  }

  after {
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()  
  }
  "OnlineBatchSync " should {
    " init client" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      assert(table.initJob[SumTestColumns](JobInfo("job55", batchId), new SumTestColumns).isSuccess)
    }
    "lock table" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      table.initJob(JobInfo("job57", batchId), new SumTestColumns())

      table.lockOnlineTable(JobInfo("job57", batchId))
      println(table.isOnlineTableLocked(JobInfo("job57", batchId)).get)
      assert(table.isOnlineTableLocked(JobInfo("job57", batchId)).get === true)
    }
    "lock table only once" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      table.initJob(JobInfo("job57", batchId), new SumTestColumns())

      table.lockOnlineTable(JobInfo("job57", batchId))
      println(table.isOnlineTableLocked(JobInfo("job57", batchId)).get)
      //assert(table.lockOnlineTable(JobInfo("job57")).get === false)
    }
    "insert and read data" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      table.initJob(JobInfo("job58", batchId), new SumTestColumns())

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.insertInOnlineTable(JobInfo("job58", batchId), 1, new RowWithValue(columns, primaryKey, indexes))

      val columnsR = new ColumnWithValue[Long]("sum", 200) :: Nil
      val columValue = table.getOnlineJobData("job58", 1, new RowWithValue(columnsR, primaryKey, indexes)).get.head.columns.head.value.toString()

      assert(columValue === "100")
    }
    "get running batch/online version " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      
      val jobInfo = JobInfo("job59", batchId)
      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))     
      table.startNextBatchJob(jobInfo)
      table.startNextOnlineJob(jobInfo)
 
      assert(table.getRunningBatchJobSlot(jobInfo).get == 1)
      assert(table.getRunningOnlineJobSlot(jobInfo).get == 1)
    }
    "switch to next job " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val jobInfo = JobInfo("job59", batchId)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))     
      table.startNextBatchJob(jobInfo)
      table.startNextOnlineJob(jobInfo)
      
      // Switching is not possible. Because an other job is running.
      assert(table.startNextBatchJob(jobInfo).isSuccess === false)
 
      assert(table.getRunningBatchJobSlot(jobInfo).get === 1)
      assert(table.getRunningOnlineJobSlot(jobInfo).get === 1)
    }
    "insert and read batch data " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val jobInfo = JobInfo("job59", batchId)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(jobInfo)
     
      
      table.insertInBatchTable(jobInfo, 0, new RowWithValue(columns, primaryKey, indexes)) 
      table.completeBatchJob(jobInfo)

      assert(table.getBatchJobData("job59", 0, new RowWithValue(columns, primaryKey, indexes)).get.head.columns.head.value === 100L)
    }
    "insert and read online data " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val jobInfo = JobInfo("job59", batchId)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))
      table.startNextOnlineJob(jobInfo)
      table.insertInOnlineTable(jobInfo, 0, new RowWithValue(columns, primaryKey, indexes)) 
      table.completeOnlineJob(jobInfo)

      assert(table.getOnlineJobData("job59", 0, new RowWithValue(columns, primaryKey, indexes)).get.head.columns.head.value === 100L)
    }
    "write and retrieve online data" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val jobInfo = JobInfo("job59", batchId)


      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))
      table.startNextOnlineJob(jobInfo)
      val oVersion = table.getRunningOnlineJobSlot(jobInfo).get
      table.insertInOnlineTable(jobInfo, oVersion, new RowWithValue(columns, primaryKey, indexes)) 
      table.completeOnlineJob(jobInfo)
      
      val version = table.getNewestOnlineSlot(jobInfo).get
      
      assert(table.getOnlineJobData(jobInfo.name, version, new RowWithValue(columns, primaryKey, indexes)).get.head.columns.head.value === sum.value)
    }
    "write and retrieve batch data" in {
      val table = new OnlineBatchSyncCassandra(dbconnection)

      val sum = new ColumnWithValue[Long]("sum", 200)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None
      val jobInfo = JobInfo("job59", batchId)

      assert(table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes)).isSuccess)
      assert(table.startNextBatchJob(jobInfo).isSuccess)
      
      val version = table.getRunningBatchJobSlot(jobInfo).get
      table.insertInBatchTable(jobInfo, version, new RowWithValue(columns, primaryKey, indexes))
      table.completeBatchJob(jobInfo)
      
      assert(table.getBatchJobData("job59", version, new RowWithValue(columns, primaryKey, indexes)).get.head.columns.head.value === sum.value)
    }
    " mark new batch job version " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val job = JobInfo("JOB_100", batchId)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      assert(table.initJob(job, new RowWithValue(columns, primaryKey, indexes)).isSuccess)
      assert(table.startNextBatchJob(job).isSuccess)

      assert(table.getBatchJobState(job, 0).get.equals(State.NEW))
      assert(table.getBatchJobState(job, 1).get.equals(State.RUNNING))
      assert(table.getBatchJobState(job, 2).get.equals(State.NEW))
    }
    " mark new online job version " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val jobInfo = JobInfo("JOB_100", batchId, 3, 3)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(jobInfo, new RowWithValue(columns, primaryKey, indexes))
      table.startNextOnlineJob(jobInfo)
      
      assert(table.getOnlineJobState(jobInfo, 0).get.equals(State.NEW))
      assert(table.getOnlineJobState(jobInfo, 1).get.equals(State.RUNNING))
      assert(table.getOnlineJobState(jobInfo, 2).get.equals(State.NEW))
    }
    " start and stop jobs " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val job = JobInfo("JOB_100", batchId)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(job, new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(job)
      assert(table.getBatchJobState(job, 1).get.equals(State.RUNNING))
      table.completeBatchJob(job)
      assert(table.getBatchJobState(job, 1).get.equals(State.COMPLETED))
    }
    " get completed tables " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      table.initJob(JobInfo("JOB_100", batchId), new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(JobInfo("JOB_100", batchId))
      table.completeBatchJob(JobInfo("JOB_100", batchId))
      
      table.initJob(JobInfo("JOB_200", batchId), new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(JobInfo("JOB_200", batchId))
      table.completeBatchJob(JobInfo("JOB_200", batchId))
      
      table.initJob(JobInfo("JOB_300", batchId), new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(JobInfo("JOB_300", batchId))
      table.completeBatchJob(JobInfo("JOB_300", batchId))
      
      table.initJob(JobInfo("JOB_400", batchId), new RowWithValue(columns, primaryKey, indexes))
      table.startNextBatchJob(JobInfo("JOB_400", batchId))
      table.completeBatchJob(JobInfo("JOB_400", batchId))
      
      table.startNextBatchJob(JobInfo("JOB_400", batchId))
      table.completeBatchJob(JobInfo("JOB_400", batchId))
     
      val expectedResults = List("JOB_100_batch1", "JOB_300_batch1", "JOB_200_batch1", "JOB_400_batch2")
      table.getQueryableTableIdentifiers.map{tableIdentifier => assert(expectedResults.contains(tableIdentifier._2.tableId))}
    }
    " reset batch job " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val job = JobInfo("JOB_100", batchId, 3, 3)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      assert(table.initJob(job, new RowWithValue(columns, primaryKey, indexes)).isSuccess)
      assert(table.startNextBatchJob(job).isSuccess)
      
      assert(table.getBatchJobState(job, 0).get.equals(State.NEW))
      assert(table.getBatchJobState(job, 1).get.equals(State.RUNNING))
      assert(table.getBatchJobState(job, 2).get.equals(State.NEW))
      
      assert(table.resetBatchJob(job).isSuccess)

      assert(table.getBatchJobState(job, 0).get.equals(State.NEW))
      assert(table.getBatchJobState(job, 1).get.equals(State.NEW))
      assert(table.getBatchJobState(job, 2).get.equals(State.NEW))
    }
    " reset online job " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)
      val job = JobInfo("JOB_100", batchId, 3, 3)

      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      assert(table.initJob(job, new RowWithValue(columns, primaryKey, indexes)).isSuccess)
      assert(table.startNextOnlineJob(job).isSuccess)
      
      assert(table.getOnlineJobState(job, 0).get.equals(State.NEW))
      assert(table.getOnlineJobState(job, 1).get.equals(State.RUNNING))
      assert(table.getOnlineJobState(job, 2).get.equals(State.NEW))
      
      assert(table.resetOnlineJob(job).isSuccess)
      assert(table.getOnlineJobState(job, 0).get.equals(State.NEW))
      assert(table.getOnlineJobState(job, 1).get.equals(State.NEW))
      assert(table.getOnlineJobState(job, 2).get.equals(State.NEW))
    }
    " reset online job " in {
      val table = new OnlineBatchSyncCassandra(dbconnection)

      val jobA = JobInfo("JOB_100", new BatchID(1460465100L, 1460465200L), 3, 3)
      val jobB = JobInfo("JOB_100", new BatchID(1460465100L, 1460465200L), 3, 3)


      val sum = new ColumnWithValue[Long]("sum", 100)
      val columns = sum :: Nil
      val primaryKey = s"(${sum.name})"
      val indexes: Option[List[String]] = None

      assert(table.initJob(jobA, new RowWithValue(columns, primaryKey, indexes)).isSuccess)
      
      assert(table.startNextBatchJob(jobA).isSuccess)
      assert(table.startNextBatchJob(jobA).isSuccess)
      
      assert(table.completeBatchJob(jobA).isSuccess)
      table.getLatestBatchSlot(jobA)
    }

    
//    "use hlist" in {
//      
//      class A {
//        type TextField <: {
//          val nr : Long
//          val r: Int
//          val chicken: Long
//        }
//      }
//      
//      
//      trait B {
//        object printlnMapper extends Poly1 {
//          implicit def default[T] = at[T](a => a.toString)
//        }
//        type C[R] <: Column[R]
//        type F012 = C[String]:: C[Long] :: C[Int] :: HNil 
//        type F1 = Column[String]:: Column[Long] :: Column[Int] :: HNil 
//        def bla(a: F012){}
//        def addRow(a: F012)(implicit ev0: Mapper[printlnMapper.type, F012])
//      }
//      
//      class R extends B {
//        type C[Int] = Column[Int]
//        
//        override def addRow(a: F012)(implicit ev0: Mapper[printlnMapper.type, F012]) {
//          a.map(printlnMapper)
//        }
//      }
//      
//      val a = new R
//      
//      val f1 = new ColumnWithValue[String]("abc", "abc") :: new ColumnWithValue[Long]("abc", 1L) :: new ColumnWithValue[Int]("abc", 1) :: HNil
//      
//      object printlnMapper extends Poly1 {
//        implicit def default[T] = at[T](a => a.toString)
//      }
//      
//      // a.addRow(f1)(implicit ev0: Mapper[a.printlnMapper.type, a.F012])
//
//      
//     
//      
//      object cyz extends ~>> {
//        def at[Int ∨ String] = 
//      }
//      
//      val b = new B(implicit ev0: Mapper[F2, ColumnWithValue, F1]) {
//        type C[R] = Column[R]
//        val p: F0 = new Column[String]("abc") :: new Column[Long]("ff") :: new Column[Int]("a") :: HNil
//        val s: F1 = new ColumnWithValue[String]("abc", "abc") :: new ColumnWithValue[Long]("abc", 1L) :: new ColumnWithValue[Int]("abc", 1) :: HNil
//        type F2 = String :: Long :: Int :: HNil 
//        def addRow(row: F2)  
//      }
//      p.m
//      
//      b.blub(p)
//      
//    }
  }
}