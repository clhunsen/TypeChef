void foo() { }
void bar() { }
void baz() { }

int main() {
#ifdef B
  void (*fp)();
  foo();
  #ifdef A
    fp = &bar;
  #else
    fp = &baz;
    (*fp)();
  #endif
   a; // bug with parser???
#endif
}