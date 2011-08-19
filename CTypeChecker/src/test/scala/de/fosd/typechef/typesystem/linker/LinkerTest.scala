package de.fosd.typechef.typesystem.linker


import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import de.fosd.typechef.parser.c._
import de.fosd.typechef.featureexpr.FeatureExpr.{base, dead}
import de.fosd.typechef.typesystem.{CVoid, CFunction, CFloat}

@RunWith(classOf[JUnitRunner])
class LinkerTest extends FunSuite with ShouldMatchers with TestHelper {

    val tfun = CFunction(Seq(), CVoid())
    val tfun2 = CFunction(Seq(CFloat()), CVoid())

    test("wellformed interfaces") {
        new CInterface(List(), List()).isWellformed should be(true)
        new CInterface(List(), List(CSignature("foo", tfun, base, Seq()))).isWellformed should be(true)
        new CInterface(List(), List(CSignature("foo", tfun, base, Seq()), CSignature("bar", tfun, base, Seq()))).isWellformed should be(true)
        new CInterface(List(), List(CSignature("foo", tfun, base, Seq()), CSignature("foo", tfun, base, Seq()))).isWellformed should be(false)
        new CInterface(List(), List(CSignature("foo", tfun, base, Seq()), CSignature("bar", tfun, base, Seq()), CSignature("foo", tfun, base, Seq()))).isWellformed should be(false)
        new CInterface(List(), List(CSignature("foo", tfun, base, Seq()), CSignature("foo", tfun2, base, Seq()))).isWellformed should be(false)
        new CInterface(List(), List(CSignature("foo", tfun, fa, Seq()), CSignature("foo", tfun, fa.not, Seq()))).isWellformed should be(true)
    }

    val ffoo = CSignature("foo", tfun, base, Seq())
    val fbar = CSignature("bar", tfun, base, Seq())
    test("simple linking") {
        val i1 = new CInterface(List(), List(ffoo))
        val i2 = new CInterface(List(), List(fbar))
        val i3 = new CInterface(List(), List(ffoo, fbar))

        (i1 link i2) should be(i3)
        (i1 link i1).featureModel should be(dead)

        ((i1 and fa) link (i1 and fa.not)).isWellformed should be(true)

        val ii = new CInterface(List(ffoo), List())
        (ii link ii) should be(ii)
        ((ii and fa) link ii) should be(ii)
        (ii link ii).isWellformed should be(true)
        (ii link new CInterface(List(fbar), List())) should be(new CInterface(List(ffoo, fbar), List()))

        (ii link i1) should be(i1)

        (CInterface(fa, List(), List()) link CInterface(fb, List(), List())) should be(CInterface(fa and fb, List(), List()))
        (CInterface(fa, List(), List()) link CInterface(fa.not, List(), List())) should be(CInterface(dead, List(), List()))
        (CInterface(fa, List(ffoo), List()) link CInterface(fa.not, List(), List())) should be(CInterface(dead, List(), List()))
        (CInterface(fa, List(), List(ffoo)) link CInterface(fa.not, List(), List())) should be(CInterface(dead, List(), List()))

        (new CInterface(List(), List(ffoo and fa)) link new CInterface(List(), List(ffoo and fb))).featureModel should be(fa mex fb)
    }

    test("complete and configured") {
        val i1 = new CInterface(List(), List(ffoo))

        i1.isComplete should be(true)

        CInterface(base, List(), List(ffoo and fa)).isFullyConfigured should be(false)
        CInterface(fa, List(), List(ffoo and fa)).isFullyConfigured should be(true)

    }

    test("packing") {
        CInterface(fa.not, List(ffoo and fa), List(ffoo and fa)).pack should be(CInterface(fa.not, List(), List()))
    }

    test("conditional composition (db example)") {
        val fwrite = CSignature("write", tfun, base, Seq())
        val fread = CSignature("read", tfun, base, Seq())
        val fselect = CSignature("select", tfun, base, Seq())
        val fupdate = CSignature("update", tfun, base, Seq())
        val idb = CInterface(base, List(fwrite, fread), List(fselect, fupdate))
        val iinmem = CInterface(base, List(), List(fwrite, fread))
        val iperist = iinmem //CInterface(base, List(),List(fwrite,fread))
        val ifm = CInterface(fa xor fb, List(), List())

        (idb isCompatibleTo iinmem) should be(true)
        (iperist isCompatibleTo iinmem) should be(false)
        println((iperist.conditional(fa) link iinmem))
        ((iperist.conditional(fa) link iinmem).featureModel implies fa.not).isTautology should be(true)
        (iperist.conditional(fa) isCompatibleTo iinmem.conditional(fb)) should be(true)
        (idb link iinmem).isComplete should be(true)
        (idb link iinmem).isFullyConfigured should be(true)
        (idb link iperist.conditional(fa) link iinmem.conditional(fb)).isComplete should be(false)

        val ifull = ifm link idb link iperist.conditional(fa) link iinmem.conditional(fb)

        ifull.isComplete should be(true)
        ifull.isFullyConfigured should be(false)
        println(ifull)
    }

}