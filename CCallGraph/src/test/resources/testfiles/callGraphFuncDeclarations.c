unsigned long long xstrtoull_range_sfx(const char *str, int b, unsigned long long l, unsigned long long u, const struct suffix_mult *sfx) ;
static __inline__ unsigned long xstrtoull_range_sfx(const char *str, int b, unsigned long l, unsigned long u, const struct suffix_mult *sfx) { return xstrtoull_range_sfx(str, b, l, u, sfx); }