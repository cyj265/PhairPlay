// JNI bridge exposing RPiPlay's reverse-engineered FairPlay key decryption to Kotlin.
// Only playfair_decrypt is wrapped; fp-setup/handshake are handled in Kotlin (FairPlay.kt).
#include <jni.h>
#include "playfair/playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_phairplay_airplay_handshake_FairPlay_nativePlayfairDecrypt(
        JNIEnv* env, jobject thiz, jbyteArray keyMessage, jbyteArray cipher) {
    (void) thiz;
    jbyte* km = (*env)->GetByteArrayElements(env, keyMessage, NULL);
    jbyte* ct = (*env)->GetByteArrayElements(env, cipher, NULL);

    unsigned char out[16];
    playfair_decrypt((unsigned char*) km, (unsigned char*) ct, out);

    (*env)->ReleaseByteArrayElements(env, keyMessage, km, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, cipher, ct, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte*) out);
    return result;
}
