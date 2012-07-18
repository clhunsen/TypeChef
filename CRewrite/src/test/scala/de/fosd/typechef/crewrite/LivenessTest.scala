package de.fosd.typechef.crewrite

import org.junit.Test
import de.fosd.typechef.parser.c.{TestHelper, PrettyPrinter, FunctionDef}
import de.fosd.typechef.featureexpr.{FeatureModel, FeatureExpr, FeatureExprFactory}
import de.fosd.typechef.featureexpr.sat.DefinedExpr
import de.fosd.typechef.conditional.Opt

class LivenessTest extends TestHelper with ConditionalControlFlow with Liveness {

  private def runExample(code: String) {
    val a = parseFunctionDef(code)

    val env = CASTEnv.createASTEnv(a)
    val ss = getAllSucc(a.stmt.innerStatements.head.entry, FeatureExprFactory.empty, env).map(_._1).filterNot(x => x.isInstanceOf[FunctionDef])

    for (s <- ss)
      println(PrettyPrinter.print(s) + "  uses: " + usesVar(s, env) + "   defines: " + definesVar(s, env) +
        "  in: " + in((s, FeatureExprFactory.empty, env)) + "   out: " + out((s, FeatureExprFactory.empty, env)))
    println("succs: " + DotGraph.map2file(getAllSucc(a, FeatureExprFactory.empty, env), env))
  }

  @Test def test_standard_liveness_example() {
    runExample("""
      void foo() {
        a = 0;
        l1: b = a + 1;
        c = c + b;
        a = b + 2;
        if (a < 20) goto l1;
        return c;
    }
    """)
  }

  @Test def test_standard_liveness_variability_f() {
    runExample("""
      void foo(int a, int b, int c) {
        a = 0;
        l1: b = a + 1;
        c = c + b;
        a = b + 2;
        if (a < 20)
          goto l1;
        return c;
    }
    """)
  }

  @Test def test_standard_liveness_variability_notf() {
    runExample("""
      void foo() {
        a = 0;
        l1: b = a + 1;
        c = c + b;
        a = b + 2;
        return c;
    }
    """)
  }

  @Test def test_standard_liveness_variability() {
    runExample("""
      void foo() {
        a = 0;
        l1: b = a + 1;
        c = c + b;
        a = b + 2;
        #ifdef F
        if (a < 20)
          goto l1;
        #endif
        return c;
    }
    """)
  }

  @Test def test_simple() {
    runExample("""
      int foo(int a, int b) {
        int c = a;
        if (c) {
          c = c + a;
          #if definedEx(A)
          c = c + b;
          #endif
        }
        return c;
    }
    """)
  }

  @Test def test_simple_A() {
    runExample("""
      int foo(int a, int b) {
        int c = a;
        if (c) {
          c = c + a;
          c = c + b;
        }
        return c;
    }
               """)
  }

  @Test def test_simple_NA() {
    runExample("""
      int foo(int a, int b) {
        int c = a;
        if (c) {
          c = c + a;
        }
        return c;
    }
               """)
  }

  @Test def test_simle2() {
    runExample("""
      int foo() {
        int a;
        int b;
        #if definedEx(A)
        int c = c + b;
        #endif
        return c;
    }
               """)
  }

  @Test def test_simle3() {
    runExample("""
      int foo() {
        int a;
        #if definedEx(A)
        int b;
        int c = c + b;
        #endif
        return c;
    }
               """)
  }
}
