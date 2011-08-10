package de.fosd.typechef.crewrite

import de.fosd.typechef.parser.c._
import de.fosd.typechef.conditional._
import de.fosd.typechef.featureexpr._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class ControlFlowGraphTest extends FunSuite with TestHelper with ShouldMatchers with VariablesImpl with ControlFlowImpl {

  private def cp(pro: p.MultiParser[AST]) = pro ^^ { One(_) }

  private def parsePrintAST(code: String) = {
    val ast = parse(code, cp(p.compoundStatement))
    println("AST: " + ast.get.asInstanceOf[One[AST]].value)
    println(PrettyPrinter.print(ast.get.asInstanceOf[One[AST]].value))
  }

  private def parsePrintGetDefines(code: String) = {
    val ast = parse(code, cp(p.compoundStatement))
    println("AST: " + ast.get)
    defines(ast.get.asInstanceOf[One[AST]].value)
  }

  private def parsePrintGetSucc(code: String) = {
    val ast = parse(code, cp(p.compoundStatement))
    println("AST: " + ast.get)
    succ(ast.get.asInstanceOf[One[AST]].value)
  }

  private def parsePrintGetPred(code: String) = {
    val ast = parse(code, cp(p.compoundStatement))
    println("AST: " + ast.get)
    pred(ast.get.asInstanceOf[One[AST]].value)
  }

//  test("twoDeclarations") {
//    parsePrintGetDefines("""
//    {
//      int k = 2;
//      int l = 3;
//    }
//    """) should be(Set(Id("k"), Id("l")))
//  }
//
//  test("multipleDeclarations") {
//    parsePrintGetDefines("""
//    {
//      int k,l = 3;
//      k = 4;
//    }
//    """) should be(Set(Id("k"), Id("l")))
//  }
//
//  test("whileLoop") {
//    parsePrintGetDefines("""
//    {
//      int k = 3;
//      while (k) {
//        int l = 2;
//      }
//    }
//    """) should be(Set(Id("k"), Id("l")))
//  }
//
//  test("ifthenelsechain") {
//    parsePrintGetDefines("""
//    {
//      int k = 3;
//      if (k < 3) {
//        k = -1;
//      }
//      else if (k = 3) {
//        k = 0;
//      }
//      else {
//        k = 1;
//      }
//    }
//    """) should be(Set(Id("k")))
//  }
//
//  test("conditionaldeclaration") {
//    parsePrintGetDefines("""
//    {
//      int k = 3;
//      #ifdef A
//      int l = 4;
//      #else
//      int m = 4;
//      #endif
//    }
//    """) should be(Set(Id("k"), Id("l"), Id("m")))
//  }
//
//  test("whileStatements") {
//    parsePrintGetDefines("""
//    {
//      while(k) { k -= 1; }
//      while(l) { l -= 1; }
//    }
//    """)
//  }
//
//  test("whilewrapper") {
//    parsePrintGetDefines("""
//    {
//      #ifdef A
//      while(k)
//      #endif
//      {
//        k -= 1;
//      }
//    }
//    """)
//  }
//
//  test("conditional labelstatements") {
//    parsePrintGetDefines("""
//    {
//      #if defined A
//        int a;
//      #elif defined B
//        int b;
//      #else
//        int c;
//      #endif
//      int d;
//    }
//    """)
//  }
//
//  test("conditional labelstatements pred elifelse") {
//    val e1 = Opt(True, LabelStatement(Id("e1"), None))
//    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
//    val e3 = Opt(fy.and(fx.not), LabelStatement(Id("e3"), None))
//    val e4 = Opt(fy.not.and(fx.not), LabelStatement(Id("e4"), None))
//    val e5 = Opt(True, LabelStatement(Id("e5"), None))
//    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))
//    e5->pred should be (Set(e2, e3, e4))
//    e4->pred should be (Set(e1))
//    e3->pred should be (Set(e1))
//    e2->pred should be (Set(e1))
//    e1->pred should be (Set(c.value))
//  }
//
//  test("conditional labelstatements succ elifelse") {
//    val e1 = Opt(True, LabelStatement(Id("e1"), None))
//    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
//    val e3 = Opt(fy.and(fx.not), LabelStatement(Id("e3"), None))
//    val e4 = Opt(fy.not.and(fx.not), LabelStatement(Id("e4"), None))
//    val e5 = Opt(True, LabelStatement(Id("e5"), None))
//    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))
//    e1->succ should be (Set(e2, e3, e4))
//    e2->succ should be (Set(e5))
//    e3->succ should be (Set(e5))
//    e4->succ should be (Set(e5))
//    e5->succ should be (Set(null))
//  }
//
//  test("conditional labelstatements pred if") {
//    val e1 = Opt(True, LabelStatement(Id("e1"), None))
//    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
//    val e3 = Opt(True, LabelStatement(Id("e3"), None))
//    val c = One(CompoundStatement(List(e1, e2, e3)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))
//    e1->pred should be (Set(c.value))
//    e2->pred should be (Set(e1))
//    e3->pred should be (Set(e1))
//  }
//
  test("conditional labelstatements isPartOfIEEChain") {
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx.not, LabelStatement(Id("e3"), None))
    val e4 = Opt(True, LabelStatement(Id("e4"), None))
    val e5 = Opt(True, LabelStatement(Id("e5"), None))
    val e6 = Opt(fx, LabelStatement(Id("e6"), None))
    val e7 = Opt(True, LabelStatement(Id("e7"), None))
    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5, e6, e7)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))

  }

  test("conditional labelstatements isPartOfIEEChain2") {
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx.not, LabelStatement(Id("e3"), None))
    val e4 = Opt(True, LabelStatement(Id("e4"), None))
    val e5 = Opt(fx, LabelStatement(Id("e5"), None))
    val e6 = Opt(fy, LabelStatement(Id("e6"), None))
    val e7 = Opt(fy.not, LabelStatement(Id("e7"), None))
    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5, e6, e7)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))

  }

  test("conditional labelstatements isPartOfIEEChain3") {
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx.not, LabelStatement(Id("e3"), None))
    val e4 = Opt(True, LabelStatement(Id("e4"), None))
    val e5 = Opt(fx, LabelStatement(Id("e5"), None))
    val e6 = Opt(fy, LabelStatement(Id("e6"), None))
    val e7 = Opt(fy.not, LabelStatement(Id("e7"), None))
    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5, e6, e7)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))

  }

  test("conditional labelstatements isPartOfIEEChain4") {
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx.not.and(fy), LabelStatement(Id("e3"), None))
    val e4 = Opt(fx.not.and(fy.not), LabelStatement(Id("e4"), None))
    val e5 = Opt(fa, LabelStatement(Id("e5"), None))
    val e6 = Opt(fa.not, LabelStatement(Id("e6"), None))
    val e7 = Opt(fb.not, LabelStatement(Id("e7"), None))
    val c = One(CompoundStatement(List(e1, e2, e3, e4, e5, e6, e7)))
//    println("AST: " + c)
//    println(PrettyPrinter.print(c.value))

  }

  test("conditional labelstatements isPartOfIEEChain5") {
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(fx, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx, LabelStatement(Id("e3"), None))
    val e4 = Opt(fx.not, LabelStatement(Id("e4"), None))
    val c = One(CompoundStatement(List(e1, e2, e3, e4)))
    println("AST: " + c)
    println(PrettyPrinter.print(c.value))
    println(e1->mysucc)
//    e1->isPartOfIEEChain should be(false)
//    e2->isPartOfIEEChain should be(true)
//    e3->isPartOfIEEChain should be(true)
//    e4->isPartOfIEEChain should be(true)
  }

  test("conditional labelstatements isPartOfIEEEChain6") {
    val e0 = Opt(fx, LabelStatement(Id("e0"), None))
    val e1 = Opt(True, LabelStatement(Id("e1"), None))
    val e2 = Opt(True, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx, LabelStatement(Id("e3"), None))
    val e4 = Opt(fx.not.and(fy), LabelStatement(Id("e4"), None))
    val e5 = Opt(fx.not.and(fy.not), LabelStatement(Id("e5"), None))
    val e6 = Opt(fa, LabelStatement(Id("e6"), None))
    val e7 = Opt(fa.not, LabelStatement(Id("e7"), None))
    val e8 = Opt(fb.not, LabelStatement(Id("e8"), None))
    val c = One(CompoundStatement(List(e0, e1, e2, e3, e4, e5, e6, e7, e8)))
    println(e1->mysucc)
  }

  test("conditional labelstatements isPartOfIEEEChain7") {
    val e0 = Opt(fx, LabelStatement(Id("e0"), None))
    val e1 = Opt(fx.not.and(fy), LabelStatement(Id("e1"), None))
    val e2 = Opt(True, LabelStatement(Id("e2"), None))
    val e3 = Opt(fx, LabelStatement(Id("e3"), None))
    val e4 = Opt(fx.not.and(fy), LabelStatement(Id("e4"), None))
    val e5 = Opt(fx.not.and(fy.not), LabelStatement(Id("e5"), None))
    val e6 = Opt(fa, LabelStatement(Id("e6"), None))
    val e7 = Opt(fa.not, LabelStatement(Id("e7"), None))
    val e8 = Opt(fb.not, LabelStatement(Id("e8"), None))
    val c = One(CompoundStatement(List(e0, e1, e2, e3, e4, e5, e6, e7, e8)))
    println(e1->mysucc)
  }
}