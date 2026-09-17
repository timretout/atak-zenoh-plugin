//! Eagerly resolves and pins the `io.zenoh.jni.*` data-class `fromParts`
//! factories at library-load time, on the thread that calls
//! `System.loadLibrary` for this library.
//!
//! On Android that thread carries the application/plugin classloader on its
//! call stack, unlike the native threads Zenoh's own Tokio runtime spawns
//! internally to deliver samples, replies and scouting results. `FindClass`
//! from one of *those* threads resolves through the system classloader and
//! cannot see APK/plugin classes, so any of these `fromParts` factories
//! resolved lazily -- on first use, from whatever thread that happens to be
//! -- SIGABRTs the process the first time delivery lands on such a thread.
//! See <https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49>.
//!
//! [`call_from_parts`] is the only entry point call sites need: it uses the
//! class pinned here when available, and falls back to a plain `find_class`
//! (the pre-existing behavior) when it isn't -- e.g. on a desktop JVM, or if
//! `JNI_OnLoad` never got a chance to run.

use std::os::raw::c_void;
use std::sync::OnceLock;

use jni::{
    objects::{GlobalRef, JObject, JValue},
    sys::{jint, JNI_VERSION_1_6},
    JNIEnv, JavaVM,
};

// Every `fromParts` factory the generated encoders call to build a `Sample`
// (or a value reachable from one) for delivery to a Kotlin callback.
const CLASS_NAMES: &[&str] = &[
    "io/zenoh/jni/pubsub/CacheConfig",
    "io/zenoh/jni/pubsub/EntityGlobalId",
    "io/zenoh/jni/pubsub/HistoryConfig",
    "io/zenoh/jni/pubsub/MissDetectionConfig",
    "io/zenoh/jni/pubsub/Miss",
    "io/zenoh/jni/pubsub/RecoveryConfig",
    "io/zenoh/jni/pubsub/RepliesConfig",
    "io/zenoh/jni/query/Selector",
    "io/zenoh/jni/sample/SourceInfo",
    "io/zenoh/jni/time/TimestampInstrumentation",
    "io/zenoh/jni/time/TimestampStackRecord",
    "io/zenoh/jni/time/Timestamp",
    "io/zenoh/jni/config/ZenohId",
];

pub(crate) const CACHE_CONFIG: usize = 0;
pub(crate) const ENTITY_GLOBAL_ID: usize = 1;
pub(crate) const HISTORY_CONFIG: usize = 2;
pub(crate) const MISS_DETECTION_CONFIG: usize = 3;
pub(crate) const MISS: usize = 4;
pub(crate) const RECOVERY_CONFIG: usize = 5;
pub(crate) const REPLIES_CONFIG: usize = 6;
pub(crate) const SELECTOR: usize = 7;
pub(crate) const SOURCE_INFO: usize = 8;
pub(crate) const TIMESTAMP_INSTRUMENTATION: usize = 9;
pub(crate) const TIMESTAMP_STACK_RECORD: usize = 10;
pub(crate) const TIMESTAMP: usize = 11;
pub(crate) const ZENOH_ID: usize = 12;

static CACHE: OnceLock<Vec<Option<GlobalRef>>> = OnceLock::new();

/// Populates [`CACHE`]. Must be called while `env`'s thread still has the
/// application classloader in its call stack (i.e. from `JNI_OnLoad`).
/// A class that fails to resolve is left as `None` and logged -- it just
/// means that one factory keeps the pre-existing (buggy-on-background-
/// threads) behavior instead of gaining the fix.
fn populate(env: &mut JNIEnv) -> Vec<Option<GlobalRef>> {
    CLASS_NAMES
        .iter()
        .map(|fqn| match env.find_class(fqn) {
            Ok(class) => match env.new_global_ref(class) {
                Ok(global) => Some(global),
                Err(e) => {
                    tracing::warn!("class_cache: pin {fqn}: {e}");
                    None
                }
            },
            Err(e) => {
                tracing::warn!("class_cache: resolve {fqn}: {e}");
                None
            }
        })
        .collect()
}

/// Calls the `fromParts` static factory named by `fqn` (JVM descriptor
/// `descr`) with `args`, preferring the class pinned by [`JNI_OnLoad`] at
/// `index` (see the constants above) and falling back to `fqn` itself --
/// a plain, uncached `find_class` -- if that slot isn't populated.
pub(crate) fn call_from_parts<'local>(
    env: &mut JNIEnv<'local>,
    index: usize,
    fqn: &str,
    descr: &str,
    args: &[JValue],
) -> Result<JObject<'local>, String> {
    let cached = CACHE.get().and_then(|c| c[index].as_ref());
    let result = match cached {
        Some(class) => env.call_static_method(class, "fromParts", descr, args),
        None => env.call_static_method(fqn, "fromParts", descr, args),
    };
    result
        .and_then(|v| v.l())
        .map_err(|e| format!("encode struct via fromParts ({fqn}): {e}"))
}

/// # Safety
/// Called by the JVM per the `JNI_OnLoad` contract: `vm` is a valid,
/// currently-attached `JavaVM*` and this runs before any other native method
/// in this library is invoked.
#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    match JavaVM::from_raw(vm) {
        Ok(jvm) => match jvm.get_env() {
            Ok(mut env) => {
                let _ = CACHE.set(populate(&mut env));
            }
            Err(e) => {
                tracing::warn!("class_cache: JNI_OnLoad could not get a JNIEnv: {e}");
            }
        },
        Err(e) => {
            tracing::warn!("class_cache: JNI_OnLoad could not wrap JavaVM: {e}");
        }
    }
    JNI_VERSION_1_6
}
