import sbt._
import Keys._

object WelcomeMessage {
  def welcomeMessage(guardrailVersion: String) = {
    import scala.Console

    def header(text: String): String = s"${Console.WHITE}${text}${Console.RESET}"

    def section(text: String): String = s"${Console.YELLOW}> ${Console.CYAN}${text}:${Console.RESET}"
    def item(text: String): String    = s"${Console.GREEN}> ${Console.CYAN}${text}${Console.RESET}"
    def subItem(text: String): String = s"  ${Console.YELLOW}> ${Console.CYAN}${text}${Console.RESET}"

    def gatesPR: String = s"${Console.YELLOW}this gates PRs being merged${Console.RESET}"

    s"""|${header("                            _           _ _ ")}
        |${header("                           | |         (_) |")}
        |${header("   __ _ _   _  __ _ _ __ __| |_ __ __ _ _| |")}
        |${header("  / _` | | | |/ _` | '__/ _` | '__/ _` | | |")}
        |${header(" | (_| | |_| | (_| | | | (_| | | | (_| | | |")}
        |${header("  \\__, |\\__,_|\\__,_|_|  \\__,_|_|  \\__,_|_|_|")}
        |${header("   __/ |")}
        |${header(s"  |___/   ${guardrailVersion}")}
        |
        |Useful sbt tasks:
        |${item("format")} - Format all code, ${gatesPR}
        |${item("testSuite")} - Run every available test suite, ${gatesPR}
        |${item("runtimeSuite")} - Similar to testSuite, but skip core tests
        |${section("Generate sample sources")}
        |${subItem("runScalaExample")} - Only generate Scala sources for integration tests
        |${subItem("runIssue")} - Only run codegen for a single issue (usage: runIssue [scala|java|*] $$framework <file>)
        |${item("publishLocal")} - Publish to local ivy repo
        |${item("publishM2")} - Publish to local m2 repo
        |      """.stripMargin
  }
}
