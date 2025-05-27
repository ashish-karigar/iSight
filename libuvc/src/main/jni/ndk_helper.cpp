/*
 * Implementation of operator delete to support older NDK code with newer NDK versions
 */

#include <cstddef>
#include <new>

// For arm64-v8a (void*, unsigned long)
void operator delete(void* ptr, unsigned long) {
    ::operator delete(ptr);
}

// For armeabi-v7a and other 32-bit architectures (void*, unsigned int) 
void operator delete(void* ptr, unsigned int) {
    ::operator delete(ptr);
} 