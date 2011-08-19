package de.fosd.typechef.typesystem


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FunSuite
import de.fosd.typechef.parser.c.TestHelper
import de.fosd.typechef.conditional._

@RunWith(classOf[JUnitRunner])
class DeclTypingTest extends FunSuite with ShouldMatchers with CTypeAnalysis with TestHelper {


    private def declTL(code: String) = {
        val ast = parseDecl(code)
        val r = declType(ast).map(e => (e._1, e._3))
        println(r)
        r
    }
    private def declCT(code: String): TConditional[CType] = declTL(code)(0)._2
    private def declT(code: String): CType = declCT(code) match {
        case TOne(e) => e
        case e => CUnknown("Multiple types not expected " + e)
    }

    test("recognizing basic types") {
        declT("int a;") should be(CSigned(CInt()))
        declT("signed int a;") should be(CSigned(CInt()))
        declT("unsigned int a;") should be(CUnsigned(CInt()))
        declT("unsigned char a;") should be(CUnsigned(CChar()))
        declT("unsigned a;") should be(CUnsigned(CInt()))
        declT("signed a;") should be(CSigned(CInt()))
        declT("double a;") should be(CDouble())
        declT("long double a;") should be(CLongDouble())

        //allow also uncommon but correct notations
        declT("char a;") should be(CSignUnspecified(CChar()))
        declT("signed char a;") should be(CSigned(CChar()))
        declT("unsigned char a;") should be(CUnsigned(CChar()))
        declT("short a;") should be(CSigned(CShort()))
        declT("short int a;") should be(CSigned(CShort()))
        declT("unsigned short a;") should be(CUnsigned(CShort()))
        declT("int a;") should be(CSigned(CInt()))
        declT("unsigned int a;") should be(CUnsigned(CInt()))
        declT("long int a;") should be(CSigned(CLong()))
        declT("unsigned long int a;") should be(CUnsigned(CLong()))
        declT("long a;") should be(CSigned(CLong()))
        declT("unsigned long a;") should be(CUnsigned(CLong()))
        declT("long long int a;") should be(CSigned(CLongLong()))
        declT("unsigned long long int a;") should be(CUnsigned(CLongLong()))
        declT("long long a;") should be(CSigned(CLongLong()))
        declT("unsigned long long a;") should be(CUnsigned(CLongLong()))
        declT("float a;") should be(CFloat())
        declT("double a;") should be(CDouble())
        declT("long double a;") should be(CLongDouble())
        declT("_Bool a;") should be(CSigned(CInt()))

        declT("int double a;").isUnknown should be(true)
        declT("signed unsigned char a;").isUnknown should be(true)
        declT("auto a;").isUnknown should be(true)
    }

    test("variable declarations") {
        declT("double a;") should be(CDouble())
        declTL("double a,b;") should be(List(("a", TOne(CDouble())), ("b", TOne(CDouble()))))
        declT("double a[];") should be(CArray(CDouble()))
        declT("double **a;") should be(CPointer(CPointer(CDouble())))
        declT("double *a[];") should be(CArray(CPointer(CDouble())))
        declT("double a[][];") should be(CArray(CArray(CDouble())))
        declT("double *a[][];") should be(CArray(CArray(CPointer(CDouble()))))
        declT("double (*a)[];") should be(CPointer(CArray(CDouble())))
        declT("double *(*a[1])();") should be(CArray(CPointer(CFunction(Seq(), CPointer(CDouble())))))

        declT("unsigned *out_len;") should be(CPointer(CUnsigned(CInt())))
    }

    test("function declarations") {
        declT("void main();") should be(CFunction(Seq(), CVoid()))
        declT("double (*fp)();") should be(CPointer(CFunction(Seq(), CDouble())))
        declT("double *fp();") should be(CFunction(Seq(), CPointer(CDouble())))
        declT("void main(double a);") should be(CFunction(Seq(CDouble()), CVoid()))
    }

    test("function declarations with abstract declarators") {
        declT("void main(double*, double);") should be(CFunction(Seq(CPointer(CDouble()), CDouble()), CVoid()))
        declT("void main(double*(), double);") should be(CFunction(Seq(CFunction(Seq(), CPointer(CDouble())), CDouble()), CVoid()))
        declT("void main(double(*(*)())());") should be(CFunction(Seq(
            CPointer(CFunction(Seq(), CPointer(CFunction(Seq(), CDouble()))))
        ), CVoid()))
    }

    test("struct declarations") {
        declT("struct { double a;} foo;").isInstanceOf[CAnonymousStruct] should be(true)
        declT("struct a foo;") should be(CStruct("a"))
        declT("struct a { double a;} foo;") should be(CStruct("a"))
        declTL("struct a;").size should be(0)

        declT("""struct mtab_list {
                    char *dir;
                    char *device;
                    struct mtab_list *next;
                } *mtl;""") should be(CPointer(CStruct("mtab_list", false)))

    }
    test("union declarations") {
        declT("union { double a;} foo;").isInstanceOf[CAnonymousStruct] should be(true)
        declT("union a foo;") should be(CStruct("a", true))
        declT("union a { double a;} foo;") should be(CStruct("a", true))
        declTL("union a;").size should be(0)
    }

    test("typeof declarations") {
        declT("typeof(int *) a;") should be(CPointer(CSigned(CInt())))
        declT("typeof(1) a;") should be(CSigned(CInt()))
    }

    test("conditional declarations") {
        declCT("int a;") should be(TOne(CSigned(CInt())))
        declCT("#ifdef X\nint\n#else\nlong\n#endif\n a;") should be(TChoice(fx.not, TOne(CSigned(CLong())), TOne(CSigned(CInt()))))
        declCT("#ifdef X\nlong\n#endif\nlong a;") should be(TChoice(fx, TOne(CSigned(CLongLong())), TOne(CSigned(CLong()))))
        declCT("long \n#ifdef X\n*\n#endif\n a;") should be(TChoice(fx, TOne(CPointer(CSigned(CLong()))), TOne(CSigned(CLong()))))
        declCT("long \n#ifdef X\n*\n#endif\n#ifdef Y\n*\n#endif\n a;") should be(
            TChoice(fy, TChoice(fx, TOne(CPointer(CPointer(CSigned(CLong())))), TOne(CPointer(CSigned(CLong())))),
                TChoice(fx, TOne(CPointer(CSigned(CLong()))), TOne(CSigned(CLong())))))
    }

}