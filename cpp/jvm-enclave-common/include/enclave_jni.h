/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class com_r3_conclave_enclave_internal_Native */

#ifndef _Included_com_r3_conclave_enclave_internal_Native
#define _Included_com_r3_conclave_enclave_internal_Native
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    jvmOcall
 * Signature: ([B)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_jvmOcall
  (JNIEnv *, jclass, jbyteArray);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    createReport
 * Signature: ([B[B[B)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_createReport
  (JNIEnv *, jclass, jbyteArray, jbyteArray, jbyteArray);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    isEnclaveSimulation
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_com_r3_conclave_enclave_internal_Native_isEnclaveSimulation
  (JNIEnv *, jclass);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    sealData
 * Signature: ([BII[BII[BII)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_sealData
  (JNIEnv *, jclass, jbyteArray, jint, jint, jbyteArray, jint, jint, jbyteArray, jint, jint);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    unsealData
 * Signature: ([BII[BII[BII)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_unsealData
  (JNIEnv *, jclass, jbyteArray, jint, jint, jbyteArray, jint, jint, jbyteArray, jint, jint);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    calcSealedBlobSize
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_com_r3_conclave_enclave_internal_Native_calcSealedBlobSize
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    authenticatedDataSize
 * Signature: ([B)I
 */
JNIEXPORT jint JNICALL Java_com_r3_conclave_enclave_internal_Native_authenticatedDataSize
  (JNIEnv *, jclass, jbyteArray);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    plaintextSizeFromSealedData
 * Signature: ([B)I
 */
JNIEXPORT jint JNICALL Java_com_r3_conclave_enclave_internal_Native_plaintextSizeFromSealedData
  (JNIEnv *, jclass, jbyteArray);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    getKey
 * Signature: ([B[B)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_getKey
  (JNIEnv *, jclass, jbyteArray, jbyteArray);

/*
 * Class:     com_r3_conclave_enclave_internal_Native
 * Method:    setupFileSystems
 * Signature: (JJLjava/lang/String;Ljava/lang/String;[B)V
 */
JNIEXPORT void JNICALL Java_com_r3_conclave_enclave_internal_Native_setupFileSystems
  (JNIEnv *, jclass, jlong, jlong, jstring, jstring, jbyteArray);

#ifdef __cplusplus
}
#endif
#endif
