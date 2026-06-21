#pragma once

#include <jni.h>

#include <cstdint>
#include <cmath>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace frostjni {

struct FrostFrame
{
    jvalue stack[256]{};
    jint sp = 0;

    jvalue locals[256]{};
};

class DescriptorUtils {
public:
    static std::string key(const char* owner, const char* name, const char* descriptor) {
        return std::string(owner) + "#" + name + descriptor;
    }
};

class ExceptionUtils {
public:
    static void throwRuntimeException(JNIEnv* env, const char* message) {
        jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message);
        }
    }

    static void throwClassCastException(JNIEnv* env, const char* type) {
        jclass exceptionClass = env->FindClass("java/lang/ClassCastException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, type);
        }
    }

    static void throwUnsupportedOperationException(JNIEnv* env, const char* message) {
        jclass exceptionClass = env->FindClass("java/lang/UnsupportedOperationException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message);
        }
    }

    static void throwArithmeticException(JNIEnv* env, const char* message) {
        jclass exceptionClass = env->FindClass("java/lang/ArithmeticException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message);
        }
    }
};

class ArrayUtils {
public:
    static jobject newMultiArray(JNIEnv* env, const char* descriptor, const jint* dimensions, jint count) {
        if (count <= 0) {
            return nullptr;
        }
        jclass arrayClass = env->FindClass(descriptor);
        if (arrayClass == nullptr) {
            return nullptr;
        }
        if (count == 1) {
            return env->NewObjectArray(dimensions[0], arrayClass, nullptr);
        }
        // Foundation implementation: allocate the outer dimension and leave
        // nested elements null until full recursive construction is added.
        return env->NewObjectArray(dimensions[0], arrayClass, nullptr);
    }
};

class StringUtils {
public:
    static std::string toStdString(JNIEnv* env, jstring value) {
        if (value == nullptr) {
            return {};
        }
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars == nullptr) {
            return {};
        }
        std::string result(chars);
        env->ReleaseStringUTFChars(value, chars);
        return result;
    }

    static jstring toJString(JNIEnv* env, const std::string& value) {
        return env->NewStringUTF(value.c_str());
    }
};

class ClassCache {
public:
    jclass get(JNIEnv* env, const char* owner) {
        std::lock_guard<std::mutex> guard(lock);
        auto found = classes.find(owner);
        if (found != classes.end()) {
            return found->second;
        }

        jclass localClass = env->FindClass(owner);
        if (localClass == nullptr) {
            return nullptr;
        }
        jclass globalClass = static_cast<jclass>(env->NewGlobalRef(localClass));
        classes.emplace(owner, globalClass);
        return globalClass;
    }

private:
    std::mutex lock;
    std::unordered_map<std::string, jclass> classes;
};

class MethodCache {
public:
    jmethodID get(
        JNIEnv* env,
        jclass ownerClass,
        const char* owner,
        const char* name,
        const char* descriptor,
        bool isStatic
    ) {
        std::lock_guard<std::mutex> guard(lock);
        std::string cacheKey = DescriptorUtils::key(owner, name, descriptor);
        auto found = methods.find(cacheKey);
        if (found != methods.end()) {
            return found->second;
        }

        if (ownerClass == nullptr) {
            return nullptr;
        }
        jmethodID method = isStatic
            ? env->GetStaticMethodID(ownerClass, name, descriptor)
            : env->GetMethodID(ownerClass, name, descriptor);
        methods.emplace(cacheKey, method);
        return method;
    }

private:
    std::mutex lock;
    std::unordered_map<std::string, jmethodID> methods;
};

class FieldCache {
public:
    jfieldID get(
        JNIEnv* env,
        jclass ownerClass,
        const char* owner,
        const char* name,
        const char* descriptor,
        bool isStatic
    ) {
        std::lock_guard<std::mutex> guard(lock);
        std::string cacheKey = DescriptorUtils::key(owner, name, descriptor);
        auto found = fields.find(cacheKey);
        if (found != fields.end()) {
            return found->second;
        }

        if (ownerClass == nullptr) {
            return nullptr;
        }
        jfieldID field = isStatic
            ? env->GetStaticFieldID(ownerClass, name, descriptor)
            : env->GetFieldID(ownerClass, name, descriptor);
        fields.emplace(cacheKey, field);
        return field;
    }

private:
    std::mutex lock;
    std::unordered_map<std::string, jfieldID> fields;
};

} // namespace frostjni
