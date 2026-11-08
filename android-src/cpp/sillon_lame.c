/* Pont Java ↔ encodeur LAME (MP3 CBR, sans étiquette : Sillon écrit la sienne) */
#include <jni.h>
#include <stdint.h>
#include "lame.h"

JNIEXPORT jlong JNICALL Java_fr_aflament_sillon_Lame_init(JNIEnv *env, jclass cls, jint inRate, jint ch, jint outRate, jint kbps) {
    lame_t l = lame_init();
    if (!l) return 0;
    lame_set_in_samplerate(l, inRate);
    lame_set_num_channels(l, ch);
    lame_set_out_samplerate(l, outRate);
    lame_set_brate(l, kbps);
    lame_set_VBR(l, vbr_off);
    lame_set_mode(l, ch == 1 ? MONO : JOINT_STEREO);
    lame_set_quality(l, 2);
    lame_set_bWriteVbrTag(l, 0);
    lame_set_write_id3tag_automatic(l, 0);
    if (lame_init_params(l) < 0) { lame_close(l); return 0; }
    return (jlong) (intptr_t) l;
}

JNIEXPORT jint JNICALL Java_fr_aflament_sillon_Lame_encode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frames, jbyteArray out) {
    lame_t l = (lame_t) (intptr_t) h;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize olen = (*env)->GetArrayLength(env, out);
    int r = lame_get_num_channels(l) == 1
            ? lame_encode_buffer(l, p, p, frames, (unsigned char *) o, olen)
            : lame_encode_buffer_interleaved(l, p, frames, (unsigned char *) o, olen);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT jint JNICALL Java_fr_aflament_sillon_Lame_flush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_t l = (lame_t) (intptr_t) h;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int r = lame_encode_flush(l, (unsigned char *) o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT void JNICALL Java_fr_aflament_sillon_Lame_close(JNIEnv *env, jclass cls, jlong h) {
    if (h) lame_close((lame_t) (intptr_t) h);
}
