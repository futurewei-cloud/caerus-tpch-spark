package main.scala

import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.{Dataset, Row}
import org.tpch.tablereader._
import org.tpch.jdbc.TpchJdbc
import org.tpch.filetype._
import org.tpch.tablereader.hdfs._
import org.tpch.pushdown.options.TpchPushdownOptions

// TPC-H table schemas
case class Customer(
  c_custkey: Long,
  c_name: String,
  c_address: String,
  c_nationkey: Long,
  c_phone: String,
  c_acctbal: Double,
  c_mktsegment: String,
  c_comment: String)

case class Lineitem(
  l_orderkey: Long,
  l_partkey: Long,
  l_suppkey: Long,
  l_linenumber: Long,
  l_quantity: Double,
  l_extendedprice: Double,
  l_discount: Double,
  l_tax: Double,
  l_returnflag: String,
  l_linestatus: String,
  l_shipdate: String,
  l_commitdate: String,
  l_receiptdate: String,
  l_shipinstruct: String,
  l_shipmode: String,
  l_comment: String)

case class Nation(
  n_nationkey: Long,
  n_name: String,
  n_regionkey: Long,
  n_comment: String)

case class Order(
  o_orderkey: Long,
  o_custkey: Long,
  o_orderstatus: String,
  o_totalprice: Double,
  o_orderdate: String,
  o_orderpriority: String,
  o_clerk: String,
  o_shippriority: Long,
  o_comment: String)

case class Part(
  p_partkey: Long,
  p_name: String,
  p_mfgr: String,
  p_brand: String,
  p_type: String,
  p_size: Long,
  p_container: String,
  p_retailprice: Double,
  p_comment: String)

case class Partsupp(
  ps_partkey: Long,
  ps_suppkey: Long,
  ps_availqty: Long,
  ps_supplycost: Double,
  ps_comment: String)

case class Region(
  r_regionkey: Long,
  r_name: String,
  r_comment: String)

case class Supplier(
  s_suppkey: Long,
  s_name: String,
  s_address: String,
  s_nationkey: Long,
  s_phone: String,
  s_acctbal: Double,
  s_comment: String)


class TpchSchemaProvider(sc: SparkContext, 
                         inputDir: String, 
                         pushOpt: TpchPushdownOptions,
                         fileType: FileType,
                         partitions: Int) {
  val dfMap = 
    if (fileType == CSVS3) 
      Map(
          "customer" -> TpchTableReaderS3.readTable[Customer]("customer.csv", inputDir, pushOpt, partitions),
          "lineitem" -> TpchTableReaderS3.readTable[Lineitem]("lineitem.csv", inputDir, pushOpt, partitions),
          "nation" -> TpchTableReaderS3.readTable[Nation]("nation.csv", inputDir, pushOpt, partitions),
          "region" -> TpchTableReaderS3.readTable[Region]("region.csv", inputDir, pushOpt, partitions),
          "orders" -> TpchTableReaderS3.readTable[Order]("order.csv", inputDir, pushOpt, partitions),
          "part" -> TpchTableReaderS3.readTable[Part]("part.csv", inputDir, pushOpt, partitions),
          "partsupp" -> TpchTableReaderS3.readTable[Partsupp]("partsupp.csv", inputDir, pushOpt, partitions),
          "supplier" -> TpchTableReaderS3.readTable[Supplier]("supplier.csv", inputDir, pushOpt, partitions) )
    else if (fileType == TBLS3)
      Map(
          "customer" -> TpchTableReaderS3.readTable[Customer]("customer.tbl", inputDir, pushOpt, partitions),
          "lineitem" -> TpchTableReaderS3.readTable[Lineitem]("lineitem.tbl", inputDir, pushOpt, partitions),
          "nation" -> TpchTableReaderS3.readTable[Nation]("nation.tbl", inputDir, pushOpt, partitions),
          "region" -> TpchTableReaderS3.readTable[Region]("region.tbl", inputDir, pushOpt, partitions),
          "orders" -> TpchTableReaderS3.readTable[Order]("orders.tbl", inputDir, pushOpt, partitions),
          "part" -> TpchTableReaderS3.readTable[Part]("part.tbl", inputDir, pushOpt, partitions),
          "partsupp" -> TpchTableReaderS3.readTable[Partsupp]("partsupp.tbl", inputDir, pushOpt, partitions),
          "supplier" -> TpchTableReaderS3.readTable[Supplier]("supplier.tbl", inputDir, pushOpt, partitions) )
    else if (fileType == JDBC)
      Map(
          "customer" -> TpchJdbc.readTable[Customer]("customer", inputDir, pushOpt, partitions),
          "lineitem" -> TpchJdbc.readTable[Lineitem]("lineitem", inputDir, pushOpt, partitions),
          "nation" -> TpchJdbc.readTable[Nation]("nation", inputDir, pushOpt, partitions),
          "region" -> TpchJdbc.readTable[Region]("region", inputDir, pushOpt, partitions),
          "orders" -> TpchJdbc.readTable[Order]("orders", inputDir, pushOpt, partitions),
          "part" -> TpchJdbc.readTable[Part]("part", inputDir, pushOpt, partitions),
          "partsupp" -> TpchJdbc.readTable[Partsupp]("partsupp", inputDir, pushOpt, partitions),
          "supplier" -> TpchJdbc.readTable[Supplier]("supplier", inputDir, pushOpt, partitions) )
    else if (fileType == CSVHdfs || fileType == CSVWebHdfs
             || fileType == TBLHdfsDs || fileType == CSVHdfsDs
             || fileType == TBLWebHdfsDs || fileType == CSVWebHdfsDs
             || fileType == TBLDikeHdfs || fileType == CSVDikeHdfs
             || fileType == TBLDikeHdfsNoProc || fileType == CSVDikeHdfsNoProc)
      Map(
          "customer" -> TpchTableReaderHdfs.readTable[Customer]("customer", inputDir, pushOpt,
                                                                partitions, fileType),
          "lineitem" -> TpchTableReaderHdfs.readTable[Lineitem]("lineitem", inputDir, pushOpt,
                                                                partitions, fileType),
          "nation" -> TpchTableReaderHdfs.readTable[Nation]("nation", inputDir, pushOpt,
                                                                partitions, fileType),
          "region" -> TpchTableReaderHdfs.readTable[Region]("region", inputDir, pushOpt,
                                                                partitions, fileType),
          "orders" -> TpchTableReaderHdfs.readTable[Order]("orders", inputDir, pushOpt,
                                                                partitions, fileType),
          "part" -> TpchTableReaderHdfs.readTable[Part]("part", inputDir, pushOpt,
                                                                partitions, fileType),
          "partsupp" -> TpchTableReaderHdfs.readTable[Partsupp]("partsupp", inputDir, pushOpt,
                                                                partitions, fileType),
          "supplier" -> TpchTableReaderHdfs.readTable[Supplier]("supplier", inputDir, pushOpt,
                                                                partitions, fileType) )
    else
      /* CSVFile, TBLFile, TBLHdfs */
      Map(
          "customer" -> TpchTableReaderFile.readTable[Customer]("customer", inputDir, fileType),
          "lineitem" -> TpchTableReaderFile.readTable[Lineitem]("lineitem", inputDir, fileType),
          "nation" -> TpchTableReaderFile.readTable[Nation]("nation", inputDir, fileType),
          "region" -> TpchTableReaderFile.readTable[Region]("region", inputDir, fileType),
          "orders" -> TpchTableReaderFile.readTable[Order]("orders", inputDir, fileType),
          "part" -> TpchTableReaderFile.readTable[Part]("part", inputDir, fileType),
          "partsupp" -> TpchTableReaderFile.readTable[Partsupp]("partsupp", inputDir, fileType),
          "supplier" -> TpchTableReaderFile.readTable[Supplier]("supplier", inputDir, fileType) )

  // for implicits
  val customer = dfMap.get("customer").get
  val lineitem = dfMap.get("lineitem").get
  val nation = dfMap.get("nation").get
  val region = dfMap.get("region").get
  val order = dfMap.get("orders").get
  val part = dfMap.get("part").get
  val partsupp = dfMap.get("partsupp").get
  val supplier = dfMap.get("supplier").get
  dfMap.foreach {
    case (key, value) => value.createOrReplaceTempView(key)
  }
}

object TpchSchemaProvider {

  var transferBytes: Long = 0
}