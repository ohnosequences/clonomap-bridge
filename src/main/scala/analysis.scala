package era7bio.asdfjkl

import era7bio.repseqmiodx._
import era7bio.asdfjkl._, loquats._, data._
import ohnosequences.loquat._
import ohnosequences.datasets._
import ohnosequences.statika._, aws._
import ohnosequences.awstools._, ec2._ , s3._, autoscaling._, regions._
import com.amazonaws.auth.profile._
import com.amazonaws.auth._
import scala.concurrent.duration._
import scala.util.Try
import java.net.URL

case class InputData(
  r1 : S3Resource,
  r2 : S3Resource
)

case class OutputData(
  baseFolder : S3Folder,
  analysisID : ID
) {
  def outputS3Folder: StepName =>
  AnyData => (AnyData, S3Resource) =
    step => data =>
      data -> baseFolder / analysisID / step / s"${analysisID}_${data.label}"
}

case class Analysis(input: InputData, output: OutputData) {

  lazy val S3 =
    s3.defaultClient

  type Email =
    String

  type ID =
    String

  // this is the one and only method you need from here
  // the output is a list of (outputname, s3address)
  def runAnalysis: Email =>
  Seq[(AnyData, S3Resource)] =
    email => {

      import impl._

      val dms = List( (dataMapping(input)(output)) )

      val fut =
        launcher.run(
          config             = defaultConfig       ,
          user               = loquatUser(email)   ,
          dataProcessing     = analysisBundle      ,
          dataMappings       = dms                 ,
          manager            = managerBundle(dms)  ,
          monitoringInterval = 10.minute
        )

      Seq(remoteOutput(output))
    }

  // there be dragons
  case object impl {

    type Namespace =
      String

    def analysisJarMetadata =
      era7bio.generated.metadata.asdfjkl
    // TODO review this. I think this should be the project which contains the data processing bundle which you want to run (??)

    type AnalysisBundle =
      allInOne.dataProcessing.type

    type DataMappings =
      List[DataMapping[AnalysisBundle]]

    def analysisBundle: AnalysisBundle =
      allInOne.dataProcessing

    def remoteOutput : OutputData =>
    Map[AnyData, S3Resource] = {
      output =>
        umiAnalysis.outputData
          .keys.types.asList
            .map( output.outputS3Folder("umi-Analysis") )
            .toMap ++
        igblastAnnotation.outputData
          .keys.types.asList
          .map( output.outputS3Folder("annotation") )
            .toMap
    }

    def dataMapping: InputData => OutputData => DataMapping[AnalysisBundle] =
      {
        input =>
        DataMapping(experimentID.value, allInOne.dataProcessing)(
          remoteInput =
            Map(
              demultiplexed.r1 -> input.r1,
              demultiplexed.r2 -> input.r2
            )
          ,
          remoteOutput = remoteOutput(output)  // TODO
        )
      }

    def managerBundle: List[DataMapping[AnalysisBundle]] => AnyManagerBundle =
      dms =>
        new ManagerBundle(worker)(dms) {

          lazy val fullName: String =
            "asdfjkl.Analysis.impl"
        }

    def defaultAMI =
      AmazonLinuxAMI(Ireland, HVM, InstanceStore)
    // TODO do I need a Manager config (??)
    object DefaultManagerConfig
      extends ManagerConfig(
        defaultAMI,
        m3.medium,
        PurchaseModel.spot(0.1)
      )
    // TODO review this conf
    case class AnalysisConfig(
      val loquatName    : String            ,
      val logsS3Prefix  : S3Folder          ,
      val managerConfig : AnyManagerConfig
    )
    extends AnyLoquatConfig {

      val metadata =
        analysisJarMetadata

      // TODO create a specific role
      val iamRoleName =
        "era7-projects"

      override
      lazy val amiEnv =
        amznAMIEnv(
          ami,
          javaHeap    = 50, // GB
          javaOptions = Seq("-XX:+UseG1GC")
        )

      override
      lazy val workersConfig: AnyWorkersConfig =
        WorkersConfig(
          defaultAMI,
          r3.`2xlarge`, // TODO should be i3.2xlarge
          PurchaseModel.spot(0.2),
          AutoScalingGroupSize(0, 1, 1)
        )

      override
      val sqsInitialTimeout =
        10.hours
    }

    val defaultConfig =
      AnalysisConfig(
        loquatName    = "data-analysis",
        logsS3Prefix  = output.baseFolder/output.analysisID/"analysis"/"log"/,
        managerConfig = DefaultManagerConfig
      )

    // worker
    //////////////////////////////////////////////////////////////////////////////
    case object worker extends WorkerBundle(
      analysisBundle,
      defaultConfig
    )

    case object workerCompat extends CompatibleWithPrefix("asdfjkl.Analysis.impl")(
      environment = defaultConfig.amiEnv,
      bundle      = worker,
      metadata    = defaultConfig.metadata
    ) {
      override lazy val fullName: String =
        "asdfjkl.Analysis.impl.workerCompat"
    }
    //////////////////////////////////////////////////////////////////////////////

    def loquatUser: Email => LoquatUser =
      email =>
        LoquatUser(
          email             = email,
          localCredentials  =
            new AWSCredentialsProviderChain(
              new InstanceProfileCredentialsProvider(false),
              new ProfileCredentialsProvider("default")
            ),
          keypairName       = "miodx-dev"
        )

    val me = LoquatUser(
      email             = "eparejatobes@ohnosequences.com",
      localCredentials  =
        new AWSCredentialsProviderChain(
          new InstanceProfileCredentialsProvider(false),
          new ProfileCredentialsProvider("default")
        ),
      keypairName       = "miodx-dev"
    )
  }
}