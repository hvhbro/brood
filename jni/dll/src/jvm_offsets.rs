// КАРТА СЛОТОВ env-таблицы (109 имён). Порт src/jvm/jvm_offsets.h 1:1,
// включая runtime-комментарии. Порядок слотов в этом форке НЕ стандартный
// (не jni.h) и меняется между билдами игры — регенерировать после каждого
// обновления (методика: tools/, см. ZNANIA раздел 3).
//
// ВАЖНО (как в оригинале): хранятся БАЙТОВЫЕ офсеты, индексация таблицы —
// через jni_slot() (/8).
//
// Форма — struct с ассоциированными константами, а не enum: в C++
// `enum class` допускает повторяющиеся значения (CallBooleanMethodA и
// CallByteMethodA оба = 1600; CallObjectMethodA и CallLongMethodA оба =
// 1560), а Rust-enum — нет. Имена и значения сохранены 1:1.

pub struct JniOffset;

/// Байты -> индекс в env-таблице (в C++ это был макрос JNI_SLOT(name)).
#[inline]
pub const fn jni_slot(bytes: u32) -> usize {
    (bytes / 8) as usize
}

#[allow(dead_code, non_upper_case_globals)]
impl JniOffset {
    pub const CallNonvirtualObjectMethodA: u32 = 40;
    pub const CallNonvirtualIntMethodA: u32 = 64;
    pub const CallStaticByteMethodA: u32 = 72;
    pub const NewGlobalRef: u32 = 96;
    pub const CallVoidMethodV: u32 = 160;
    pub const GetBooleanField: u32 = 176;
    pub const NewObject: u32 = 184;
    pub const GetStaticFloatField: u32 = 192;
    pub const GetStaticDoubleField: u32 = 200;
    pub const NewStringUTF: u32 = 208;
    pub const CallNonvirtualFloatMethodA: u32 = 216;
    pub const CallStaticCharMethodA: u32 = 224;
    pub const CallStaticIntMethodA: u32 = 256;
    pub const DeleteLocalRef: u32 = 272;
    pub const GetCharField: u32 = 288;
    pub const GetObjectArrayElement: u32 = 296;
    pub const GetStaticShortField: u32 = 336;
    pub const CallNonvirtualDoubleMethodA: u32 = 344;
    pub const ExceptionClear: u32 = 368;
    pub const CallIntMethodA: u32 = 416; // runtime-верифицирован
    pub const CallBooleanMethodA: u32 = 1600; // runtime: слот 54 неверен, 200 работает
    pub const GetByteArrayRegion: u32 = 448;
    pub const CallShortMethodA: u32 = 456;
    pub const SetIntArrayRegion: u32 = 464;
    pub const GetFloatArrayRegion: u32 = 488;
    pub const CallObjectMethodA: u32 = 1560; // runtime-верифицирован (слот 195)
    pub const CallStaticBooleanMethodA: u32 = 576;
    pub const GetArrayLength: u32 = 584;
    pub const SetDoubleArrayRegion: u32 = 592;
    pub const NewLocalRef: u32 = 640;
    pub const GetStaticObjectField: u32 = 648;
    pub const GetStringUTFLength: u32 = 656;
    pub const SetCharArrayRegion: u32 = 664;
    pub const CallStaticLongMethodA: u32 = 680;
    pub const CallStaticVoidMethodA: u32 = 736;
    pub const Throw: u32 = 744;
    pub const SetLongArrayRegion: u32 = 752;
    pub const ExceptionDescribe: u32 = 800;
    pub const GetDoubleArrayRegion: u32 = 832;
    pub const CallStaticDoubleMethodA: u32 = 864;
    pub const GetLongArrayRegion: u32 = 872;
    pub const GetSuperclass: u32 = 880;
    pub const PopLocalFrame: u32 = 888;
    pub const CallDoubleMethodA: u32 = 904;
    pub const GetObjectField: u32 = 920;
    pub const CallCharMethodA: u32 = 936;
    pub const GetStaticBooleanField: u32 = 944;
    pub const NewObjectA: u32 = 960;
    pub const PushLocalFrame: u32 = 984;
    pub const CallStaticShortMethodA: u32 = 1000;
    pub const GetIntField: u32 = 1016;
    pub const SetBooleanArrayRegion: u32 = 1048;
    pub const IsAssignableFrom: u32 = 1064;
    pub const GetShortField: u32 = 1072;
    pub const GetStaticMethodID: u32 = 1080;
    pub const CallNonvirtualByteMethodA: u32 = 1088;
    pub const GetJavaVM: u32 = 1096;
    pub const CallVoidMethodA: u32 = 1104;
    pub const CallNonvirtualBooleanMethodA: u32 = 1128;
    pub const IsVirtualThread: u32 = 1152;
    pub const CallNonvirtualVoidMethodV: u32 = 1160;
    pub const GetStringUTFChars: u32 = 1168;
    pub const GetObjectClass: u32 = 1192;
    pub const GetLongField: u32 = 1200;
    pub const NewObjectArray: u32 = 1232;
    pub const SetShortArrayRegion: u32 = 1256;
    pub const IsInstanceOf: u32 = 1264;
    pub const SetObjectArrayElement: u32 = 1272;
    pub const ThrowNew: u32 = 1280;
    pub const CallStaticFloatMethodA: u32 = 1288;
    pub const SetByteArrayRegion: u32 = 1320;
    pub const GetShortArrayRegion: u32 = 1328;
    pub const CallStaticObjectMethodA: u32 = 1336;
    pub const CallNonvirtualVoidMethodA: u32 = 1376;
    pub const GetCharArrayRegion: u32 = 1392;
    pub const GetStaticLongField: u32 = 1400;
    pub const NewWeakGlobalRef: u32 = 1416;
    pub const CallNonvirtualLongMethodA: u32 = 1424;
    pub const GetByteField: u32 = 1456;
    pub const CallStaticVoidMethod: u32 = 1480;
    pub const DeleteGlobalRef: u32 = 1488;
    pub const CallStaticVoidMethodV: u32 = 1520;
    pub const CallVoidMethod: u32 = 1536;
    pub const CallFloatMethodA: u32 = 1552;
    pub const CallLongMethodA: u32 = 1560;
    pub const GetFloatField: u32 = 1568;
    pub const FromReflectedMethod: u32 = 1576;
    pub const CallByteMethodA: u32 = 1600;
    pub const CallNonvirtualShortMethodA: u32 = 1608;
    pub const SetFloatArrayRegion: u32 = 1616;
    pub const GetStaticIntField: u32 = 1632;
    pub const CallNonvirtualVoidMethod: u32 = 1640;
    pub const GetBooleanArrayRegion: u32 = 1648;
    pub const GetStaticByteField: u32 = 1656;
    pub const GetDoubleField: u32 = 1664;
    pub const GetStaticCharField: u32 = 1672;
    pub const CallObjectMethod: u32 = 1688;
    pub const FatalError: u32 = 1704;
    pub const CallNonvirtualCharMethodA: u32 = 1720;
    pub const ExceptionOccurred: u32 = 1728;
    pub const GetIntArrayRegion: u32 = 1744;
    pub const DeleteWeakGlobalRef: u32 = 1800;
    pub const FromReflectedField: u32 = 1808;
    pub const IsSameObject: u32 = 1816;
    pub const ToReflectedMethod: u32 = 1824;
    pub const ReleaseStringUTFChars: u32 = 1832;
    pub const DefineClass: u32 = 552;
    pub const FindClass: u32 = 1840;
    pub const GetMethodID: u32 = 1864; // runtime-верифицирован
}
