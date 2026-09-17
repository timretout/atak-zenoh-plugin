# libzenoh_flat_jni.so patch: Android plugin-classloader crash

`app/src/main/jniLibs/<abi>/libzenoh_flat_jni.so` in this plugin is **not**
the stock binary from the `org.eclipse.zenoh:zenoh-flat-jni-android` Maven
artifact. It's a locally patched build that fixes a crash which is otherwise
guaranteed the first time the plugin receives *any* CoT message over Zenoh
with a timestamp attached (i.e. essentially always, with a real publisher).

## The bug

Upstream, tracked at
[eclipse-zenoh/zenoh-flat-jni#49](https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49).
Confirmed against `zenoh-kotlin-android:1.10.1` (what this plugin depends
on) by disassembling the crash's native stack trace back to the exact Rust
source line.

Zenoh's native library constructs Java objects (`io.zenoh.jni.time.Timestamp`
and 12 others — see the class list in `class_cache.rs`) from its own Tokio
worker threads when delivering a sample, a query reply, or a scouting
result. Each construction does a fresh, uncached `FindClass` via
`JNIEnv::call_static_method("io/zenoh/jni/…", "fromParts", …)`. On Android,
`FindClass` from a thread with no Java frames on its stack (i.e. one Zenoh's
own async runtime created, not one the JVM created) resolves through the
**system classloader**, which cannot see the plugin's own classes — ATAK
loads each plugin, and its dependencies, through its own `PluginClassLoader`,
separate from the app's. The resulting `ClassNotFoundException` is thrown
while another JNI call is still in flight, which is a fatal
`JNI DETECTED ERROR IN APPLICATION`, and the whole ATAK process aborts
(`SIGABRT`).

This is specific to being loaded as a secondary/plugin classloader, which is
presumably why it wasn't caught upstream — a normal standalone Android app
has only one classloader for all its own code, so this native thread's
implicit classloader is never "wrong" there.

## The fix

`class_cache.rs` (new file) adds a `JNI_OnLoad` that runs synchronously on
whichever thread calls `System.loadLibrary` for this library — on Android
that's a real Java-created thread with the plugin's actual classloader on
its stack, since it's *our* Kotlin/Java code that triggers the load. It
eagerly resolves and pins (as `GlobalRef`s, valid from any thread
thereafter) all 13 `io.zenoh.jni.*` classes the generated bindings construct
this way.

`build.rs` is patched (`patch_class_cache()`, called right after
`generation.write_rust(...)`) to rewrite each generated `fromParts` call
site to prefer the pinned class from `class_cache::get`, falling back to a
plain `find_class` — the original behavior — if a class wasn't pinned (e.g.
on a desktop JVM, where this bug doesn't apply). This step is necessary
because `src/generated_bindings.rs` is regenerated from scratch by `build.rs`
on every build (via the `prebindgen` codegen), so hand-patching that file
directly doesn't survive a rebuild — `patch_class_cache()` runs every time
instead, so the fix is idempotent and durable.

`Cargo.toml` just adds `regex` as a build-dependency, used by
`patch_class_cache()`.

## Reproducing the build

Requires the Android NDK (this was built against r30) and `cargo-ndk`
(`cargo install cargo-ndk`), plus the `aarch64-linux-android`,
`armv7-linux-androideabi`, `i686-linux-android` and `x86_64-linux-android`
Rust targets added to whatever toolchain `zenoh-flat-jni`'s
`rust-toolchain.toml` pins (check the file after cloning — it was `1.97.1`
at the time of this patch; `rustup target add --toolchain <that> <targets>`).

```bash
git clone https://github.com/eclipse-zenoh/zenoh-flat-jni.git
cd zenoh-flat-jni
git checkout 1.10.1   # match the zenoh-kotlin-android version in app/build.gradle

cp /path/to/this/native/zenoh-flat-jni-patch/class_cache.rs src/class_cache.rs
cp /path/to/this/native/zenoh-flat-jni-patch/build.rs build.rs
cp /path/to/this/native/zenoh-flat-jni-patch/lib.rs src/lib.rs
cp /path/to/this/native/zenoh-flat-jni-patch/Cargo.toml Cargo.toml

export ANDROID_NDK_HOME=/path/to/android-ndk-r30
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o /tmp/zenoh-jni-out build --release --locked
# drops libzenoh_flat_jni.so under /tmp/zenoh-jni-out/<abi>/ for each target
```

Copy the resulting `.so` files over
`app/src/main/jniLibs/<abi>/libzenoh_flat_jni.so` in this plugin.
`app/build.gradle`'s `packagingOptions.jniLibs.pickFirsts` is already set so
Gradle prefers these over the ones pulled in transitively by the
`zenoh-kotlin-android` Maven dependency.

## When to drop this

Once upstream ships a release with a fix for #49, bump the
`zenoh-kotlin-android` / `zenoh-flat-jni` versions in `app/build.gradle` and
delete `app/src/main/jniLibs/**/libzenoh_flat_jni.so`, the `pickFirsts`
entry in `app/build.gradle`, and this directory.
