package main.scala

import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat.getIntegerInstance
import org.apache.spark.sql._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer
import scala.reflect.runtime.universe._
import org.apache.spark.sql.catalyst.ScalaReflection
import scopt.OParser
import org.apache.hadoop.fs._
import com.github.datasource.s3.S3StoreCSV
import com.github.datasource.parse._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.tpch.tablereader._
import org.tpch.tablereader.hdfs._
import org.tpch.filetype._
import org.tpch.pushdown.options.TpchPushdownOptions
import org.tpch.jdbc.TpchJdbc

/**
 * Parent class for TPC-H queries.
 *
 * Defines schemas for tables and reads pipe ("|") separated text files into these tables.
 *
 * Savvas Savvides <savvas@purdue.edu>
 *
 */
abstract class TpchQuery {

  // get the name of the class excluding dollar signs and package
  private def escapeClassName(className: String): String = {
    val items = className.split("\\.")
    val last = items(items.length-1)
    last.replaceAll("\\$", "")
  }

  def getName(): String = escapeClassName(this.getClass.getName)

  /**
   *  implemented in children classes and hold the actual query
   */
  def execute(sc: SparkContext, tpchSchemaProvider: TpchSchemaProvider): DataFrame
}

object TpchQuery {

  private val sparkConf = new SparkConf().setAppName("Simple Application")
  private val sparkContext = new SparkContext(sparkConf)
    
  /** Writes the dataframe to disk.
   *
   *  @param df - the dataframe to output
   *  @param outputDir - path to use in output
   *  @param className - the name of the test class
   *  @param config - The configuration of the tst.
   *  @return String - Path to output results.
   */
  def outputDF(df: DataFrame, outputDir: String, className: String,
               config: Config): Unit = {

    if (outputDir == null || outputDir == "")
      df.collect().foreach(println)
    else {      
      val castColumns = (df.schema.fields map { x =>
        if (x.dataType == DoubleType) {
          format_number(bround(col(x.name), 3), 2)
        } else {
          col(x.name)
        }      
      }).toArray

      if (!className.contains("17") && config.checkResults) {       
        df.sort((df.columns.toSeq map { x => col(x) }).toArray:_*)
            .select(castColumns:_*)
            .repartition(1)
            .write.mode("overwrite")
            .format("csv")
            .option("header", "true")
            .option("partitions", "1")
            .save(outputDir + "/" + className)
      } else {
        df.repartition(1)
          .write.mode("overwrite")
          .format("csv")
          .option("header", "true")
          .option("partitions", "1")
          .save(outputDir + "/" + className)
      }
    }
  }

  /** Fetches the directory name to be used for output of the resultant
   *  dataframe.
   *
   *  @param config - The configuration of the tst.
   *  @return String - Path to output results.
   */
  def getOutputDir(config: Config): String = { 
    var outputDir = "file:///build/tpch-results/latest/" + config.mode.toString
    if (config.partitions != 0) {
      outputDir += "-partitions-1"
    }
    if (config.s3Filter && config.s3Project) {
      outputDir += "-PushdownFilterProject"
    } else if (config.pushdown) {
      outputDir += "-PushdownAgg"
    } else if (config.s3Filter) {
      outputDir += "-PushdownFilter"
    } else if (config.s3Project) {
      outputDir += "-PushdownProject"
    }
    outputDir += "-W" + config.workers
    outputDir
  }
  def executeQueries(schemaProvider: TpchSchemaProvider, 
                     queryNum: Int,
                     config: Config): ListBuffer[(String, Float)] = {

    
    val results = new ListBuffer[(String, Float)]
    val outputDir: String = getOutputDir(config)
    val t0 = System.nanoTime()

    val query = Class.forName(f"main.scala.Q${queryNum}%02d")
                      .newInstance.asInstanceOf[TpchQuery]
    val df = query.execute(sparkContext, schemaProvider)
    if (config.explain) {
      df.explain(true)
      //println("Num Partitions: " + df.rdd.partitions.length)
    }
    outputDF(df, outputDir, query.getName(), config)

    val t1 = System.nanoTime()

    val elapsed = (t1 - t0) / 1000000000.0f // second
    results += new Tuple2(query.getName(), elapsed)
    return results
  }

  case class Config(
    var start: Int = 0,
    testNumbers: String = "",
    var end: Int = -1,
    var testList: ArrayBuffer[Integer] = ArrayBuffer.empty[Integer],
    repeat: Int = 0,
    partitions: Int = 0,
    workers: Int = 1,
    checkResults: Boolean = false,
    var fileType: FileType = CSVS3,
    mode: String = "",  // The mode of the test.
    format: String = "tbl",
    datasource: String = "spark",
    protocol: String = "hdfs",
    filePart: Boolean = false,
    var init: Boolean = false,
    var pushdownOptions: TpchPushdownOptions = new TpchPushdownOptions(false, false, false, false),
    pushdown: Boolean = false,
    s3Filter: Boolean = false,
    s3Project: Boolean = false,
    s3Aggregate: Boolean = false,
    debugData: Boolean = false,
    verbose: Boolean = false,
    explain: Boolean = false,
    quiet: Boolean = false,
    normal: Boolean = false,
    kwargs: Map[String, String] = Map())

  /** Validates and processes args related to the type of test.
   *  One major piece we acomplish is determining the fileType,
   *  which is a description of the type of test to perform,
   *  which is used by the rest of the test.
   *  
   *  @param config - The program config to be validated.
   *  @return Boolean - true if valid, false if invalid config.
   */
  def processTestMode(config: Config): Boolean = {
    config.datasource match {
      case "ndp" if (config.protocol == "s3" &&
                     config.format == "csv") => config.fileType = CSVS3
      case "spark" if (config.protocol == "file" &&
                     config.format == "csv") => config.fileType = CSVFile
      case "spark" if (config.protocol == "file" &&
                     config.format == "tbl") => config.fileType = TBLFile
      case "spark" if (config.protocol == "hdfs" &&
                     config.format == "csv") => config.fileType = CSVHdfs
      case "spark" if (config.protocol == "hdfs" &&
                     config.format == "tbl") => config.fileType = TBLHdfs
      case "ndp" if (config.protocol == "hdfs" &&
                     config.format == "csv") => config.fileType = CSVHdfsDs
      case "ndp" if (config.protocol == "hdfs" &&
                     config.format == "tbl")  => config.fileType = TBLHdfsDs
      case "ndp" if (config.protocol == "webhdfs" &&
                     config.format == "csv") => config.fileType = CSVWebHdfsDs
      case "ndp" if (config.protocol == "webhdfs" &&
                     config.format == "tbl") => config.fileType = TBLWebHdfsDs
      case "ndp" if (config.protocol == "ndphdfs" &&
                     config.format == "csv") => config.fileType = CSVDikeHdfs
      case "ndp" if (config.protocol == "ndphdfs" &&
                     config.format == "tbl") => config.fileType = TBLDikeHdfs
      case "spark" if (config.protocol == "webhdfs" &&
                     config.format == "tbl") => config.fileType = TBLWebHdfs
      case "spark" if (config.protocol == "webhdfs" &&
                     config.format == "tbl") => config.fileType = CSVWebHdfs
      case "ndp" if (config.protocol == "s3" && config.format == "tbl" && 
                     config.filePart == true) => config.fileType = TBLS3
      case "ndp" if (config.protocol == "s3" && config.format == "tbl") => config.fileType = TBLS3
      case ds if config.mode == "jdbc" => config.fileType = JDBC
      case ds if config.mode == "init" || config.mode == "initJdbc" => {
        config.init = true
        config.fileType = TBLFile
      }
      case test => println(s"Unknown test configuration: test: ${test} format: ${config.format} protocol: ${config.protocol}" +
                           s" datasource: ${config.datasource}")
                   return false
    }
    return true
  }
  /** Parse the test numbers argument and generate a list of integers
   *  with the test numbers to run.
   *  @param config - The configuration of the tst.
   *  @return Boolean - true on success, false, validation failed.
   */
  def processTestNumbers(config: Config) : Boolean = {
    if (config.testNumbers != "") {
      val ranges = config.testNumbers.split(",")
      for (r <- ranges) {
        if (r.contains("-")) {
          val numbers = r.split("-")
          if (numbers.length == 2) {
            for (i <- numbers(0).toInt to numbers(1).toInt) {
              config.testList += i
            }
          }
        } else {
          val test = r.toInt
          config.testList += test
        }
      }
    }
    for (t <- config.testList) {
      if (t < 1 || t > 22) {
        println(s"test numbers must be 1..22.  ${t} is not a valid test")
        return false
      }
    }
    true
  }  
  /** Parse the pushdown related arguments and generate
   *  the config.pushdownOptions.
   *
   *  @param config - The configuration of the tst.
   *  @return Unit
   */
  def processPushdownOptions(config: Config) : Unit = {
    if (config.pushdown) {
      config.pushdownOptions = TpchPushdownOptions(true, true, true, config.explain)
    } else {
      config.pushdownOptions = TpchPushdownOptions(config.s3Filter,
                                                    config.s3Project,
                                                    config.s3Aggregate,
                                                    config.explain)
    }
  }
  private val usageInfo = """The program has two main modes, one where we are using
  *) --mode init or --mode initJdbc or --mode jdbc.  In this case
     the test is initializing a database for example to
     convert the database to .csv or to a JDBC format.
  *) otherwise the program will be running the tpch benchmark
     and the parameters below determine the test to run, and
     with which configuration to use such as: 
     --format (csv | tbl)
     --protocol (file | s3 | hdfs | webhdfs | ndphdfs)
     --datasource (spark | ndp)
     -t (test number)"""
  /** Parses all the test arguments and forms the
   *  config object, which is used to convey the test parameters.
   *  
   *  @param args - The test arguments from the user.
   *  @return Config - The object representing all program params.
   */
  def parseArgs(args: Array[String]): Config = {
  
    val builder = OParser.builder[Config]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("TCPH Benchmark"),
        head("tpch-test", "0.1"),
        note(usageInfo + sys.props("line.separator")),
        opt[String]('t', "test")
          .action((x, c) => c.copy(testNumbers = x))
          .valueName("<test number>")
          .text("test numbers. e.g. 1,2-5,6,7,9-11,16-22"),
         opt[Int]('p', "partitions")
          .action((x, c) => c.copy(partitions = x.toInt))
          .valueName("<number of partitions>")
          .text("partitions to use"),
         opt[Int]('w', "workers")
          .action((x, c) => c.copy(workers = x.toInt))
          .valueName("<number of spark workers>")
          .text("workers being used"),
        opt[String]("mode")
          .action((x, c) => c.copy(mode = x))
          .valueName("<test mode>")
          .text("test mode (jdbc, init, initJdbc)")
          .validate( mode =>
            mode match {
              case "jdbc" => success
              case "init" => success
              case _ => failure("mode must be jdbc, init or InitJdbc")
            }),
        opt[String]('f', "format")
          .action((x, c) => c.copy(format = x))
          .valueName("<file format>")
          .text("file format to use (csv, tbl")
          .validate(f =>
            f match {
              case "tbl" => success
              case "csv"  => success
              case format => failure(s"ERROR: format: ${format} not suported")
            }),
        opt[String]("datasource")
          .abbr("ds")
          .valueName("<datasource>")
          .action((x, c) => c.copy(datasource = x))
          .text("datasource to use (spark, ndp)")
          .validate(datasource =>
            datasource match {
              case "spark" => success
              case "ndp"  => success
              case ds => failure(s"datasource: ${ds} not suported")
            }),
        opt[String]('r', "protocol")
          .action((x, c) => c.copy(protocol = x))
          .valueName("<protocol>")
          .text("server protocol to use (file, s3, hdfs, webhdfs, ndphdfs)")
          .validate(protocol =>
            protocol match {
              case "file" => success
              case "s3" => success
              case "hdfs" => success
              case "webhdfs" => success
              case "ndphdfs" => success
              case protocol => failure(s"ERROR: protocol: ${protocol} not suported")
            }),
        opt[Unit]("filePart")
          .action((x, c) => c.copy(filePart = true))
          .text("Use file based partitioning."),
        opt[Unit]("pushdown")
          .action((x, c) => c.copy(pushdown = true))
          .text("Enable all pushdowns (filter, project, aggregate), default is disabled."),
        opt[Unit]("s3Filter")
          .action((x, c) => c.copy(s3Filter = true))
          .text("Enable s3Select pushdown of filter, default is disabled."),
        opt[Unit]("s3Project")
          .action((x, c) => c.copy(s3Project = true))
          .text("Enable s3Select pushdown of project, default is disabled."),
        opt[Unit]("s3Aggregate")
          .action((x, c) => c.copy(s3Aggregate = true))
          .text("Enable s3Select pushdown of aggregate, default is disabled."),
        opt[Unit]("check")
          .action((x, c) => c.copy(checkResults = true))
          .text("Enable checking of results."),
        opt[Unit]("verbose")
          .action((x, c) => c.copy(verbose = true))
          .text("Enable verbose Spark output (TRACE log level )."),
        opt[Unit]("explain")
          .action((x, c) => c.copy(explain = true))
          .text("Run explain on the df prior to query."),
        opt[Unit]('q', "quiet")
          .action((x, c) => c.copy(quiet = true))
          .text("Limit output (WARN log level)."),
        opt[Unit]("normal")
          .action((x, c) => c.copy(normal = true))
          .text("Normal log output (INFO log level)."),
        opt[Unit]("debugData")
          .action((x, c) => c.copy(debugData = true))
          .text("For debugging, copy the data output to file."),
        opt[Int]('r', "repeat")
          .action((x, c) => c.copy(repeat = x.toInt))
          .valueName("<repeat count>")
          .text("Number of times to repeat test"),
        help("help").text("prints this usage text"),
        checkConfig(
          c => {
            var status: Boolean = processTestMode(c)
            status &= processTestNumbers(c)
            processPushdownOptions(c)
            if (!status) {
              failure("Validation failed.")
            } else {
              if ((c.mode == "") && (c.testNumbers == "")) {
                failure("must select either --mode or --test")
              } else {
                success
            }}})
      )
    }
    // OParser.parse returns Option[Config]
    val config = OParser.parse(parser1, args, Config())
    
    config match {
        case Some(config) => config
        case _ =>
          // arguments are bad, error message will have been displayed
          System.exit(1)
          new Config
    }
  }

  case class TpchTestResult (
    test: Integer,
    seconds: Double,
    bytesTransferred: Double)

  /** Shows the results from a ListBuffer[TpchTestResult]
   *
   * @param results - The test results.
   * @return Unit
   */
  private def showResults(results: ListBuffer[TpchTestResult]) : Unit = {
    val formatter = java.text.NumberFormat.getIntegerInstance
    println("Test Results")
    println("Test    Time (sec)             Bytes")
    println("------------------------------------")
    for (r <- results) {
      val bytes = formatter.format(r.bytesTransferred)
      println(f"${r.test}%4d, ${r.seconds}%10.3f," +
              f" ${r.bytesTransferred}%20.0f")
    }
  }
  /** Fetch the path to be used to input data.
   *
   *  @param config - The test configuration.
   *  @return Unit
   */
  def inputPath(config: Config) = {
      config.datasource match {
        case ds if (ds == "spark" && config.format == "tbl" &&
                    config.protocol == "file") => "file:///tpch-data/tpch-test"
        case ds if (ds == "spark" && config.format == "csv" &&
                    config.protocol == "file") => "file:///tpch-data/tpch-test-csv"
        case ds if (ds == "ndp" && config.format == "tbl" &&
                    config.protocol == "hdfs") => "hdfs://dikehdfs/tpch-test/"
        case ds if (ds == "ndp" && config.format == "csv" &&
                    config.protocol == "hdfs") => "hdfs://dikehdfs/tpch-test-csv/"
        case ds if (ds == "ndp" && config.format == "tbl" &&
                    config.protocol == "webhdfs") => "webhdfs://dikehdfs/tpch-test/"
        case ds if (ds == "ndp" && config.format == "csv" &&
                    config.protocol == "webhdfs") => "webhdfs://dikehdfs/tpch-test-csv/"
                    
        case ds if (ds == "spark" && config.format == "tbl" &&
                    config.protocol == "hdfs") => "hdfs://dikehdfs:9000/tpch-test/"
        case ds if (ds == "spark" && config.format == "csv" &&
                    config.protocol == "hdfs") => "hdfs://dikehdfs:9000/tpch-test-csv/"
        case ds if (ds == "spark" && config.format == "tbl" &&
                    config.protocol == "webhdfs") => "webhdfs://dikehdfs:9870/tpch-test/"
        case ds if (ds == "spark" && config.format == "csv" &&
                    config.protocol == "webhdfs") => "webhdfs://dikehdfs:9870/tpch-test-csv/"

        case ds if (ds == "ndp" && config.format == "tbl" &&
                    config.protocol == "ndphdfs") => "ndphdfs://dikehdfs/tpch-test/"
        case ds if (ds == "ndp" && config.format == "csv" &&
                    config.protocol == "ndphdfs") => "ndphdfs://dikehdfs/tpch-test-csv/"
                    
        case ds if (ds == "ndp" && config.format == "tbl" &&
                    config.filePart) => "s3a://tpch-test-part"
        case ds if (ds == "ndp" && config.format == "tbl" &&
                    config.protocol == "s3") => "s3a://tpch-test"

        case x if config.mode == "jdbc" => "file://tpch-data/tpch-test-jdbc"
      }
  }

  /** Sets the file to be used to output when we are debugging data.
   *
   * @param config - test configuration.
   * @param test - name of the test
   * @return Unit
   */
  def setDebugFile(config: Config, test: String) : Unit = {
    if (config.debugData) {
      val outputDir = "/build/tpch-results/data/"
      val directory = new File(outputDir)
      if (! directory.exists()) {
        directory.mkdir()
        println("creating data dir")
      }
      RowIterator.setDebugFile(outputDir + config.fileType.toString + "-" + test)
    }
  }

  /** Runs the benchmark, and displayes the results.
   *
   * @param config - test configuration.
   * @return Unit
   */
  def benchmark(config: Config): Unit = {
    var totalMs: Long = 0
    var results = new ListBuffer[TpchTestResult]
    val outputDir: String = getOutputDir(config)
   
    if (FileType.isHdfs(config.fileType)) {
      TpchTableReaderHdfs.init(config.fileType)
    } else {
     S3StoreCSV.resetTransferLength
    }
    println(s"InputPath: ${inputPath(config)}")
    val schemaProvider = new TpchSchemaProvider(sparkContext, inputPath(config), 
                                                config.pushdownOptions, config.fileType,
                                                config.partitions)
    for (r <- 0 to config.repeat) {
      for (i <- config.testList) {
        val output = new ListBuffer[(String, Float)]
        println("Starting Q" + i)
        setDebugFile(config, i.toString)
        val start = System.currentTimeMillis()
        output ++= executeQueries(schemaProvider, i, config)
        val end = System.currentTimeMillis()
        val ms = (end - start)
        if (r != 0) totalMs += ms
        val seconds = ms / 1000.0
        val statsType = FileType.getStatsType(config.fileType)
        if (statsType.contains("hdfs")) {
          results += TpchTestResult(i, seconds, TpchTableReaderHdfs.getStats(statsType).getBytesRead)
        } else if (statsType == "file") {
          results += TpchTestResult(i, seconds, TpchSchemaProvider.transferBytes)
        } else if (statsType == "s3") {
          results += TpchTestResult(i, seconds, S3StoreCSV.getTransferLength)
        } else {
          results += TpchTestResult(i, seconds, 0)
        }
        S3StoreCSV.resetTransferLength
        println("Query Time " + seconds)
        val outFile = new File("/tmp/TIMES" + i + ".txt")
        val bw = new BufferedWriter(new FileWriter(outFile, true))

        output.foreach {
          case (key, value) => bw.write(f"${key}%s\t${value}%1.8f\n")
        }

        bw.close()
        showResults(results)
      }
    }
    if (config.repeat > 1) {
      val averageSec = (totalMs / 1000.0) / config.repeat
      println("Average Seconds per Test: " + averageSec)
    }
  }

  val initTblPath = "file:///tpch-data/tpch-test"

  /** Initializes a new database using csv.
   *
   * @param config - test configuration.
   * @return Unit
   */
  def init(config: Config): Unit = {
    val schemaProvider = new TpchSchemaProvider(sparkContext, initTblPath, 
                                                config.pushdownOptions, config.fileType,
                                                config.partitions)
    for ((name, df) <- schemaProvider.dfMap) {
      val outputFolder = "/build/tpch-data/" + name + "raw"
      df.repartition(1)
        .write
        .option("header", true)
        .option("partitions", "1")
        .format("csv")
        .save(outputFolder)

      val fs = FileSystem.get(sparkContext.hadoopConfiguration)
      val file = fs.globStatus(new Path(outputFolder + "/part-0000*"))(0).getPath().getName()
      println(outputFolder + "/" + file + "->" + "/build/tpch-data/" + name + ".csv")
      fs.rename(new Path(outputFolder + "/" + file), new Path("/build/tpch-data/" + name + ".csv"))
      println("Finished writing " + name + ".csv")
    }
    println("Finished converting *.tbl to *.csv")
  }
  /** Initializes a JDBC H2 database with content from a tpch database.
   *  This reads in a database (for exmaple from .tbl files)
   *  and then writes it out into the JDBC database.
   *
   * @param config - The configuration of the test.
   * @return Unit
   */
  def initJdbc(config: Config): Unit = {
    val h2Database = "file:///tpch-data/tpch-jdbc/tpch-h2-database"
    val schemaProvider = new TpchSchemaProvider(sparkContext, initTblPath, 
                                                config.pushdownOptions, config.fileType,
                                                config.partitions)
    TpchJdbc.setupDatabase()
    for ((name, df) <- schemaProvider.dfMap) {
        TpchJdbc.writeDf(df, name, h2Database)
        //TpchJdbc.readDf(name).show()
    }
    println("Finished converting *.tbl to jdbc:h2 format")
  }

  /** This is the main entry point of the program,
   *  see above parseArgs for more usage information.
   *
   * @param config - The configuration of the test.
   * @return Unit
   */
  def main(args: Array[String]): Unit = {

    val config = parseArgs(args)
    println("args: " + args.mkString(" "))
    println("pushdown: " + config.pushdown)
    println("pushdown options: " + config.pushdownOptions)
    println("workers: " + config.workers)
    println("mode: " + config.mode)
    println("fileType: " + config.fileType)
    println("start: " + config.start)
    println("end: " + config.end)

    if (config.verbose) {
      sparkContext.setLogLevel("TRACE")
    } else if (config.quiet) {
      sparkContext.setLogLevel("WARN")
    } else if (config.normal) {
      sparkContext.setLogLevel("INFO")
    }
    config.mode match {
      case "init" => init(config)
      case "initJdbc" => initJdbc(config)
      case _ => benchmark(config)
    }
  }
}
