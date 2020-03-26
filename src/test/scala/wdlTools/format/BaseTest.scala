package wdlTools.format

import collection.JavaConverters._
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{FlatSpec, Matchers}

//import wdlTools.syntax.ConcreteSyntax._
import wdlTools.syntax.ParseDocument
import wdlTools.util.Options
//import wdlTools.util.Verbosity.Quiet

class BaseTest extends FlatSpec with Matchers {

  private def getWdlSource(fname: String): String = {
    val p: String = getClass.getResource(s"/format/${fname}").getPath
    val path: Path = Paths.get(p)
    Files.readAllLines(path).asScala.mkString(System.lineSeparator())
  }
  private lazy val conf = Options(antlr4Trace = false)


  it should "handle the runtime section correctly" in {
    val doc = ParseDocument.apply(getWdlSource("simple.wdl"), conf)
    doc.version shouldBe ("1.0")
  }
}
