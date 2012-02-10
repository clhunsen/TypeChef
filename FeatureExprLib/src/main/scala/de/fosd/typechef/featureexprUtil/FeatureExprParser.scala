package de.fosd.typechef.featureexprUtil

import util.parsing.combinator._
import java.io._
import de.fosd.typechef.featureexpr.FeatureExpr
import de.fosd.typechef.featureexpr.FeatureExprFactory


/**
 * simple parser to read feature expressions in the format produced by FeatureExpr.print(...)
 *
 * does not support integer values yet
 */
class FeatureExprParser extends RegexParsers {

    def toFeature(name: String) = FeatureExprFactory.createDefinedExternal(name)


    //implications
    def expr: Parser[FeatureExpr] =
        "oneOf" ~ "(" ~> rep1sep(expr, ",") <~ ")" ^^ {
            e => oneOf(e)
        } | "atLeastOne" ~ "(" ~> rep1sep(expr, ",") <~ ")" ^^ {
            e => atLeastOne(e)
        } | aterm

    def aterm: Parser[FeatureExpr] =
        bterm ~ opt(("=>" | "implies") ~> expr) ^^ {
            case a ~ b => if (b.isDefined) a implies b.get else a
        }

    def bterm: Parser[FeatureExpr] =
        cterm ~ opt(("<=>" | "equiv") ~> expr) ^^ {
            case a ~ b => if (b.isDefined) a equiv b.get else a
        }

    //mutually exclusion
    def cterm: Parser[FeatureExpr] =
        dterm ~ opt(("<!>" | "mex") ~> expr) ^^ {
            case a ~ b => if (b.isDefined) a mex b.get else a
        }

    //||
    def dterm: Parser[FeatureExpr] =
        term ~ rep(("||" | "|" | "or") ~> expr) ^^ {
            case a ~ bs => bs.foldLeft(a)(_ or _)
        }

    def term: Parser[FeatureExpr] =
        bool ~ rep(("&&" | "&" | "and") ~> expr) ^^ {
            case a ~ bs => bs.foldLeft(a)(_ and _)
        }

    def bool: Parser[FeatureExpr] =
        "!" ~> bool ^^ (_ not) |
            ("(" ~> expr <~ ")") |
            "InvalidExpression()" ^^ (_ => FeatureExprFactory.dead) |
            (("definedEx" | "defined" | "def") ~ "(" ~> ID <~ ")") ^^ {
                toFeature(_)
            } |
            "1" ^^ {
                x => FeatureExprFactory.base
            } |
            "0" ^^ {
                x => FeatureExprFactory.dead
            }

    def ID = "[A-Za-z0-9_]*".r

    def parse(featureExpr: String): FeatureExpr = parseAll(expr, featureExpr) match {
        case Success(r, _) => r
        case NoSuccess(msg, _) => throw new Exception("error parsing " + featureExpr + " " + msg)
    }

    def parse(featureExpr: Reader): FeatureExpr = parseAll(expr, featureExpr) match {
        case Success(r, _) => r
        case NoSuccess(msg, _) => throw new Exception("error parsing " + msg)
    }

    private def trimComment(l: String): String = {
        if (l.indexOf("//") >= 0)
            l.take(l.indexOf("//"))
        else l
    }


    def parseFile(cfilename: String): FeatureExpr = {
        val featureModelFile = new File(cfilename)
        if (featureModelFile.exists) {
            scala.io.Source.fromFile(featureModelFile).getLines().map(trimComment(_)).map(line =>
                if (line.trim.isEmpty) FeatureExprFactory.base
                else parse(line)
            ).fold(FeatureExprFactory.base)(_ and _)
        } else FeatureExprFactory.base
    }

    def parseFile(file: File): FeatureExpr =
        new FeatureExprParser().parse(new FileReader(file))


    private def oneOf(features: List[FeatureExpr]): FeatureExpr = {
        (for (f1 <- features; f2 <- features if (f1 != f2)) yield f1 mex f2).
            foldLeft(features.foldLeft(FeatureExprFactory.dead)(_ or _))(_ and _)

    }

    def atLeastOne(featuresNames: List[FeatureExpr]): FeatureExpr =
        featuresNames.foldLeft(FeatureExprFactory.dead)(_ or _)

}