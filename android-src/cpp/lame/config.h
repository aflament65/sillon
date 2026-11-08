/* Configuration minimale de LAME 3.100 pour Android (compilé par le NDK, sans ./configure) */
#ifndef SILLON_LAME_CONFIG_H
#define SILLON_LAME_CONFIG_H
#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_UNISTD_H 1
#define HAVE_MEMORY_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1
#define SIZEOF_SHORT 2
#define SIZEOF_UNSIGNED_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_UNSIGNED_INT 4
#define SIZEOF_LONG __SIZEOF_LONG__
#define SIZEOF_UNSIGNED_LONG __SIZEOF_LONG__
#define SIZEOF_LONG_LONG 8
#define SIZEOF_FLOAT 4
#define SIZEOF_DOUBLE 8
#define SIZEOF_LONG_DOUBLE __SIZEOF_LONG_DOUBLE__
#define HAVE_INT8_T 1
#define HAVE_INT16_T 1
#define HAVE_INT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT8_T 1
#define HAVE_UINT16_T 1
#define HAVE_UINT32_T 1
#define HAVE_UINT64_T 1
#define A_UINT32_T unsigned int
#define A_UINT64_T unsigned long long
#define HAVE_IEEE754_FLOAT32_T 0
typedef float ieee754_float32_t;
#define USE_FAST_LOG 1
#define TAKEHIRO_IEEE754_HACK 1
#define PACKAGE "lame"
#define VERSION "3.100"
#define PROTOTYPES 1
#endif
