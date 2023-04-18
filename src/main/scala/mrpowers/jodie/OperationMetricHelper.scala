package mrpowers.jodie

import mrpowers.jodie.delta.{DeleteMetric, MergeMetric, OperationMetrics, WriteMetric}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.delta.util.FileNames
import org.apache.spark.sql.delta.{DeltaHistory, DeltaLog}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{LongType, MapType, StringType, StructType}
import org.apache.spark.sql.{DataFrame, Encoders, SparkSession}

case class OperationMetricHelper(
    path: String,
    startingVersion: Long = 0,
    endingVersion: Option[Long] = None
) {
  val spark                 = SparkSession.active
  val deltaLog              = DeltaLog.forTable(spark, path)
  private val metricColumns = Seq("version", "deleted", "inserted", "updated", "source_rows")

  def getMetricsAsDF(): DataFrame = {
    import spark.implicits._
    getTransactionMetrics.toDF(metricColumns: _*)
  }

  def getMetricsAsDF(partitionCondition: String): DataFrame = {
    import spark.implicits._
    getTransactionMetrics(partitionCondition).toDF(metricColumns: _*)
  }

  def getTransactionMetrics: Seq[(Long, Long, Long, Long, Long)] = transformMetric(
    generateMetric(deltaLog.history.getHistory(startingVersion, endingVersion))
  )

  def getTransactionMetrics(
      partitionCondition: String
  ): Seq[(Long, Long, Long, Long, Long)] = transformMetric(
    generateMetric(
      deltaLog.history
        .getHistory(startingVersion, endingVersion)
        .filter(x => filterHistoryByPartition(x, partitionCondition)),
      Some(partitionCondition)
    )
  )

  def getWriteMetricByPartition(
      partitionCondition: String,
      version: Long
  ): Long = {
    import spark.implicits._
    val trimmed  = partitionCondition.trim
    val splitCondition =
      if (trimmed.contains(" and "))
        trimmed.split(" and ").toSeq
      else Seq(trimmed)
    val conditions = splitCondition.map(x => {
      val kv = x.split("=")
      assert(kv.size == 2)
      s"${kv.head.trim}=${kv.tail.head.trim.stripPrefix("\'").stripSuffix("\'")}"
    })
    val jsonSchema = new StructType()
      .add("numRecords", LongType)
      .add("minValues", MapType(StringType, StringType))
      .add("maxValues", MapType(StringType, StringType))
      .add("nullCount", MapType(StringType, StringType))
    spark.read
      .json(FileNames.deltaFile(deltaLog.logPath, version).toString)
      .withColumn("stats", org.apache.spark.sql.functions.from_json(col("add.stats"), jsonSchema))
      .select("add.path", "stats")
      .map(x => {
        val path = x.getAs[String]("path")
        conditions.map(x => path != null && path.contains(x)).reduceOption(_ && _) match {
          case None => 0L
          case Some(bool) =>
            if (bool)
              x.getAs[String]("stats").asInstanceOf[GenericRowWithSchema].getAs[Long]("numRecords")
            else 0L
        }
      })(Encoders.scalaLong)
      .reduce(_ + _)
  }

  def transformMetric(
      metric: Seq[(Long, OperationMetrics)]
  ): Seq[(Long, Long, Long, Long, Long)] = metric
    .filter(x =>
      x._2.isInstanceOf[MergeMetric] || x._2.isInstanceOf[WriteMetric] || x._2
        .isInstanceOf[DeleteMetric]
    )
    .map(x => {
      val (deleted, inserted, updated, sourceRows) = x._2 match {
        case MergeMetric(a, b, c, d, e, f, g, h, i, j, k, l) => (b, e, g, j)
        case WriteMetric(a, b, c)                            => (0L, b, 0L, b)
        case DeleteMetric(a, b, c, d, e, f, g, h, i, j)      => (a, 0L, 0L, 0L)
        case _ =>
          throw new IllegalArgumentException("Other Metric Types are not allowed in this method")
      }
      Tuple5.apply(x._1, deleted, inserted, updated, sourceRows)
    })

  private def filterHistoryByPartition(x: DeltaHistory, partitionCondition: String): Boolean =
    x.operation match {
      case "WRITE" => true
      case "DELETE" | "MERGE" =>
        if (
          x.operationParameters
            .contains("predicate") && x.operationParameters.get("predicate") != None
        ) {
          if (partitionCondition.trim.toLowerCase().contains(" and ")) {
            val conditions = partitionCondition.split(" and ")
            conditions.map(y => evaluateCondition(x, y)).reduce(_ && _)
          } else evaluateCondition(x, partitionCondition)
        } else {
          false
        }
      case _ => false
    }

  private def evaluateCondition(x: DeltaHistory, partitionCondition: String) = {
    x.operationParameters.get("predicate").get.contains(partitionCondition.trim)
  }
  private def generateMetric(
      deltaHistories: Seq[DeltaHistory],
      partitionCondition: Option[String] = None
  ): Seq[(Long, OperationMetrics)] =
    deltaHistories
      .map(dh => {
        (
          dh.version.get, {
            val metrics = dh.operationMetrics.get
            dh.operation match {
              case "MERGE" =>
                MergeMetric(
                  numTargetRowsCopied = metrics("numTargetRowsCopied").toLong,
                  numTargetRowsDeleted = metrics("numTargetRowsDeleted").toLong,
                  numTargetFilesAdded = metrics("numTargetFilesAdded").toLong,
                  executionTimeMs = metrics("executionTimeMs").toLong,
                  numTargetRowsInserted = metrics("numTargetRowsInserted").toLong,
                  scanTimeMs = metrics("scanTimeMs").toLong,
                  numTargetRowsUpdated = metrics("numTargetRowsUpdated").toLong,
                  numOutputRows = metrics("numOutputRows").toLong,
                  numTargetChangeFilesAdded = metrics("numTargetChangeFilesAdded").toLong,
                  numSourceRows = metrics("numSourceRows").toLong,
                  numTargetFilesRemoved = metrics("numTargetFilesRemoved").toLong,
                  rewriteTimeMs = metrics("rewriteTimeMs").toLong
                )
              case "WRITE" =>
                partitionCondition match {
                  case None =>
                    WriteMetric(
                      numFiles = metrics("numFiles").toLong,
                      numOutputRows = metrics("numOutputRows").toLong,
                      numOutputBytes = metrics("numOutputBytes").toLong
                    )
                  case Some(condition) =>
                    WriteMetric(0L, getWriteMetricByPartition(condition, dh.version.get), 0L)
                }
              case "DELETE" =>
                DeleteMetric(
                  numDeletedRows = whenContains(metrics, "numDeletedRows"),
                  numAddedFiles = whenContains(metrics, "numAddedFiles"),
                  numCopiedRows = whenContains(metrics, "numCopiedRows"),
                  numRemovedFiles = whenContains(metrics, "numRemovedFiles"),
                  numAddedChangeFiles = whenContains(metrics, "numAddedChangeFiles"),
                  numRemovedBytes = whenContains(metrics, "numRemovedBytes"),
                  numAddedBytes = whenContains(metrics, "numAddedBytes"),
                  executionTimeMs = whenContains(metrics, "executionTimeMs"),
                  scanTimeMs = whenContains(metrics, "scanTimeMs"),
                  rewriteTimeMs = whenContains(metrics, "rewriteTimeMs")
                )
              case _ => null
            }
          }
        )
      })
      .filter(x => x._2 != null)

  private def whenContains(map: Map[String, String], key: String) =
    if (map.contains(key)) map(key).toLong else 0L
}
