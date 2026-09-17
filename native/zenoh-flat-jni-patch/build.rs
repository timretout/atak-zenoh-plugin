//! Build script for the zenoh-flat JNI/Kotlin layer.
//!
//! Reads the `#[prebindgen]` items captured by `zenoh-flat` and drives them
//! through the [`prebindgen_jni::JniGen`] adapter to produce:
//!   * `src/generated_bindings.rs` — the Rust-side JNI wrappers (included by
//!     `src/lib.rs`), and
//!   * `kotlin/generated/**` — the matching typed Kotlin classes.
//!
//! ## Model
//!
//! This is the **flat tier**: every `#[prebindgen]` free function is declared
//! as a package function (`package!("x").fun(fun!(...))`) under a module
//! namespace, and each `PackageDecl` batch is handed to [`JniGen::package`].
//! Opaque handles stay typed Kotlin classes derived from `NativeHandle`
//! (locked, closeable) via `.class(ptr_class!(T))`.
//!
//! A type's default boundary shape is declared once, per direction, at the
//! generator level and AUTO-APPLIES to every matching param / return: input
//! variants via `.expand(expand_param!(T).variant(fun!(ctor)).variant_self())`
//! (an OR-list, runtime-selected) and output fields via
//! `.expand(expand_return!(T).field(fun!(acc)).field_self())` (an
//! AND-set, one crossing). A `.field(fun!(acc))` inherits its Kotlin name from
//! the class member declaration of the same fn. Class members are declared
//! with `.method(fun!(f))` (instance methods) and `.constructor(fun!(f))`
//! (companion factories); the per-fn `.expand_param(name, …)` /
//! `.expand_return(…)` overrides (chained on the `fun!` decl, taking the same
//! expand-decl objects) replace the defaults — an identity-only set
//! (`.variant_self()` / `.field_self()` alone) is the plain raw-handle form.
//!
//! Kotlin **method names are derived automatically** by the
//! `set_method_name_mangle` hook ([`strip_flat_class_prefix`], which strips the
//! class-name prefix): `sample_get_payload` → `getPayload`, `keyexpr_as_str`
//! → `asStr`, `keyexpr_new_join` → `newJoin`. An explicit `.name(...)` is
//! used only where absolutely necessary: `toStr` (a derived `toString` would
//! clash with Kotlin's `Any.toString()`) and the `message` field label of the
//! class-less rust-side-only `Error` decomposition.
//!
//! Where a multi-variant param crosses as the string-or-handle `KeyExpr`
//! idiom, `.split_on_param("key_expr")` emits idiomatic typed overloads
//! (`f(keyExpr: String, …)` / `f(keyExpr: KeyExpr, …)`) over the selector form,
//! so callers pass a value directly instead of a `(selector, …)` tuple.
//!
//! Errors are delivered through the per-call `onError` callback (no Rust-side
//! JVM throw): `Error` (zenoh's native error) is the `E` of every fallible
//! `Result<_, Error>`, and its default return field (`error_get_message ->
//! String`) auto-applies to the `E` position so `onError` receives the message.
//!
//! Names mirror zenoh-flat's de-prefixed Rust identifiers one-to-one
//! (`KeyExpr`, `Session`, `keyexpr_new_try_from`, `open`, …); the Kotlin-side
//! names are derived from them automatically.

use prebindgen_jni::{
    data_class, enum_class, matching, package, ptr_class, sealed_class, ConstDecl, FunctionDecl,
    JniGen,
};
use prebindgen_registry::{convert, expand_param, expand_return, fields, fun, sig};
use syn::parse_quote as pq;

fn fail(context: &str, err: impl std::fmt::Display) -> ! {
    eprintln!("error: prebindgen jnigen {context}: {err}");
    std::process::exit(1);
}

/// Rewrites each generated `fromParts` factory call site --
/// `env.call_static_method("io/zenoh/jni/…", "fromParts", DESCR, ARGS)`,
/// `.and_then(|__v| __v.l())`, `.map_err(…format!("encode struct via
/// fromParts: {}", e)…)` -- to route through
/// `class_cache::call_from_parts`, which prefers the class `JNI_OnLoad`
/// pinned (see `src/class_cache.rs`) over a plain `find_class`. Idempotent:
/// re-running this against its own output finds nothing to replace, so it's
/// safe to always call after every `write_rust`, on every build, regardless
/// of caching.
///
/// Fix for <https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49>:
/// `find_class` from one of Zenoh's own Tokio worker threads (sample/reply
/// delivery, scouting) resolves through the system classloader, which can't
/// see APK/plugin classes on Android, and SIGABRTs the process. `JNI_OnLoad`
/// runs on the thread that calls `System.loadLibrary`, which does have the
/// application classloader on its stack, so classes pinned there resolve
/// correctly from any thread afterwards.
fn patch_class_cache(rust_path: &std::path::Path) {
    // (class FQN, index constant in src/class_cache.rs) for every
    // `fromParts` factory the generated encoders call from a thread that
    // might be one of Zenoh's own.
    const CLASSES: &[(&str, &str)] = &[
        ("io/zenoh/jni/pubsub/CacheConfig", "CACHE_CONFIG"),
        ("io/zenoh/jni/pubsub/EntityGlobalId", "ENTITY_GLOBAL_ID"),
        ("io/zenoh/jni/pubsub/HistoryConfig", "HISTORY_CONFIG"),
        ("io/zenoh/jni/pubsub/MissDetectionConfig", "MISS_DETECTION_CONFIG"),
        ("io/zenoh/jni/pubsub/Miss", "MISS"),
        ("io/zenoh/jni/pubsub/RecoveryConfig", "RECOVERY_CONFIG"),
        ("io/zenoh/jni/pubsub/RepliesConfig", "REPLIES_CONFIG"),
        ("io/zenoh/jni/query/Selector", "SELECTOR"),
        ("io/zenoh/jni/sample/SourceInfo", "SOURCE_INFO"),
        ("io/zenoh/jni/time/TimestampInstrumentation", "TIMESTAMP_INSTRUMENTATION"),
        ("io/zenoh/jni/time/TimestampStackRecord", "TIMESTAMP_STACK_RECORD"),
        ("io/zenoh/jni/time/Timestamp", "TIMESTAMP"),
        ("io/zenoh/jni/config/ZenohId", "ZENOH_ID"),
    ];

    let source = match std::fs::read_to_string(rust_path) {
        Ok(s) => s,
        Err(err) => fail("patch_class_cache: read generated_bindings.rs", err),
    };

    // DOTALL (`(?s)`) so `.` spans the multi-line `&[ … ]` args block; `.*?`
    // is non-greedy so each match stops at its own call's closing `],`.
    let pattern = regex::Regex::new(concat!(
        r#"(?s)env\s*\n\s*\.call_static_method\(\s*\n"#,
        r#"\s*"(?P<class>io/zenoh/jni/[^"]+)",\s*\n"#,
        r#"\s*"fromParts",\s*\n"#,
        r#"\s*"(?P<descr>[^"]+)",\s*\n"#,
        r#"(?P<args>\s*&\[.*?\],\s*\n)"#,
        r#"\s*\)\s*\n"#,
        r#"\s*\.and_then\(\|__v\| __v\.l\(\)\)\s*\n"#,
        r#"\s*\.map_err\(\|e\| <__JniErr as ::core::convert::From<\s*\n"#,
        r#"\s*String,\s*\n"#,
        r#"\s*>>::from\(format!\("encode struct via fromParts: \{\}", e\)\)\)\?;"#,
    ))
    .unwrap();

    let mut replaced = std::collections::HashSet::new();
    let patched = pattern.replace_all(&source, |caps: &regex::Captures| {
        let class = &caps["class"];
        let descr = &caps["descr"];
        let args = &caps["args"];
        let const_name = CLASSES
            .iter()
            .find(|(fqn, _)| *fqn == class)
            .map(|(_, c)| *c)
            .unwrap_or_else(|| fail("patch_class_cache", format!("unrecognized fromParts class {class}; add it to CLASSES in build.rs and to class_cache.rs")));
        replaced.insert(class.to_string());
        format!(
            "crate::class_cache::call_from_parts(\n                env,\n                crate::class_cache::{const_name},\n                \"{class}\",\n                \"{descr}\",\n{args}            )\n            .map_err(|e| <__JniErr as ::core::convert::From<\n                String,\n            >>::from(e))?;"
        )
    });

    let missing: Vec<&str> = CLASSES
        .iter()
        .map(|(fqn, _)| *fqn)
        .filter(|fqn| !replaced.contains(*fqn))
        .collect();
    if !missing.is_empty() {
        fail(
            "patch_class_cache",
            format!(
                "expected fromParts call sites not found for: {missing:?} \
                 (upstream codegen output shape changed? update the pattern in build.rs)"
            ),
        );
    }

    if let Err(err) = std::fs::write(rust_path, patched.as_ref()) {
        fail("patch_class_cache: write generated_bindings.rs", err);
    }
    println!(
        "cargo:warning=class_cache: routed {} fromParts call site(s) through the JNI_OnLoad class cache",
        replaced.len()
    );
}

/// Namespace-relative member naming: strip the (case-insensitive) class-name
/// prefix from a class method's derived name so the flat crate's
/// `keyexpr_as_str` surfaces as `asStr` on `KeyExpr`, `zbytes_to_bytes` as
/// `toBytes` on `ZBytes`, etc. Registered via
/// [`JniGen::set_method_name_mangle`] — the generator's default method mangle
/// is identity (full camelCase), so this hook restores the de-prefixed API.
/// Members with an explicit `.name(...)` bypass the hook.
fn strip_flat_class_prefix(class: &str, name: &str) -> String {
    if name
        .get(..class.len())
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case(class))
    {
        let rest = &name[class.len()..];
        let mut chars = rest.chars();
        if let Some(first) = chars.next() {
            return first.to_lowercase().chain(chars).collect();
        }
    }
    name.to_string()
}

/// The two expression constants for one predefined encoding, composed from
/// zenoh-flat's general accessors over its `encoding_const_<name>()` loaning
/// factory (no per-preset accessor exists in the source crate):
/// `ENCODING_<NAME>: String` (canonical form, upstream `Display`) and
/// `ENCODING_<NAME>_ID: Int` (numeric wire id).
fn encoding_consts(lower: &str) -> [ConstDecl; 2] {
    let upper = lower.to_uppercase();
    let factory: syn::Ident = syn::parse_str(&format!("encoding_const_{lower}")).unwrap();
    [
        ConstDecl::named(format!("ENCODING_{upper}"))
            .expr(pq!(String), pq!(encoding_to_string(#factory()))),
        ConstDecl::named(format!("ENCODING_{upper}_ID"))
            .expr(pq!(u16), pq!(encoding_get_id(#factory()))),
    ]
}

fn main() {
    // Declarations are plain values (`PackageDecl` / class decls / `fun!`
    // decls) handed to `JniGen::package` batch by batch — no typestate
    // cursor, so the long encoding-constant list below is an ordinary loop
    // over an ordinary `PtrClassDecl` binding.

    // ── Bytes: Encoding ───────────────────────────────────────────────
    // Canonical input: encoding params cross as their decomposed value
    // `(id: i32, schema: Option<String>)` (built via `encoding_new_from_id`)
    // — cheap primitives, no per-call String parse, no native handle.
    // Canonical output: the handle (identity) + id (raw jint), both free
    // jvalue slots; schema and the canonical string stay on-demand accessors.
    // `.gc_managed()`: an Encoding handle lives inside a non-closeable SDK
    // value (zenoh-java/zenoh-kotlin `Encoding`) that nobody will ever
    // close — the shared Cleaner frees it when the value becomes
    // unreachable; explicit close/take/consumption settle the ticket first.
    let encoding = ptr_class!(Encoding)
        .gc_managed()
        .method(fun!(encoding_get_id))
        .method(fun!(encoding_get_schema))
        .method(fun!(encoding_to_string).name("toStr"))
        // Whole-handle clone return — see KeyExpr's `newClone`.
        .method(fun!(encoding_new_clone).expand_return(expand_return!(Encoding).field_self()))
        // `encoding_new_with_schema(&Encoding, schema) -> Encoding` derives a new
        // Encoding — a companion factory returning a raw handle (a constructor
        // never return-field-decomposes, so the result stays a usable handle rather than
        // a decomposed builder).
        .constructor(fun!(encoding_new_with_schema))
        // Factories → companion members; `fromId` is also the input variant
        // (see the `expand_param!(Encoding)` declaration below).
        .constructor(fun!(encoding_new_from_id))
        .constructor(fun!(encoding_new_from_string));

    // ── Bytes package: ZBytes + Encoding + predefined-encoding consts ────
    // ZBytes canonical input: `payload`/`attachment` params accept a `ByteArray`
    // (built via `zbytes_new_from_vec`). Canonical output: the handle only
    // (identity) — the bytes are heavy and fetched on demand via
    // `zbytesAsBytes` (one borrow-copy).
    //
    // Predefined encoding constants — each surfaces as a pair of
    // lazily-initialized top-level `val`s in the bytes package (pure JVM
    // values, no native handle), both **expression constants**
    // (`.expr`-sourced constants): this binding composes zenoh-flat's general
    // accessors over the `encoding_const_<name>()` loaning factory
    // (`encoding_to_string(...)` / `encoding_get_id(...)`), evaluated once
    // on first access — the source crate exposes each preset exactly once and
    // stores no decomposed values (see `encoding_consts` above). The loaning
    // factories themselves are not bound as Kotlin members (see the
    // `ignore` declaration below).
    let mut bytes = package!("bytes")
        .class(
            ptr_class!(ZBytes)
                .method(fun!(zbytes_to_bytes))
                // Whole-handle clone return — see KeyExpr's `newClone`.
                .method(fun!(zbytes_new_clone).expand_return(expand_return!(ZBytes).field_self()))
                // `fromVec` builds a ZBytes from a `ByteArray` — both the
                // param-variant build arm (see `expand_param!(ZBytes)` below)
                // AND a companion factory (a constructor never
                // return-field-decomposes, so the factory keeps its
                // raw-handle return).
                .constructor(fun!(zbytes_new_from_vec)),
        )
        .class(encoding);
    for lower in [
        "zenoh_bytes",
        "zenoh_string",
        "zenoh_serialized",
        "application_octet_stream",
        "text_plain",
        "application_json",
        "text_json",
        "application_cdr",
        "application_cbor",
        "application_yaml",
        "text_yaml",
        "text_json5",
        "application_python_serialized_object",
        "application_protobuf",
        "application_java_serialized_object",
        "application_openmetrics_text",
        "image_png",
        "image_jpeg",
        "image_gif",
        "image_bmp",
        "image_webp",
        "application_xml",
        "application_x_www_form_urlencoded",
        "text_html",
        "text_xml",
        "text_css",
        "text_javascript",
        "text_markdown",
        "text_csv",
        "application_sql",
        "application_coap_payload",
        "application_json_patch_json",
        "application_json_seq",
        "application_jsonpath",
        "application_jwt",
        "application_mp4",
        "application_soap_xml",
        "application_yang",
        "audio_aac",
        "audio_flac",
        "audio_mp4",
        "audio_ogg",
        "audio_vorbis",
        "video_h261",
        "video_h263",
        "video_h264",
        "video_h265",
        "video_h266",
        "video_mp4",
        "video_ogg",
        "video_raw",
        "video_vp8",
        "video_vp9",
    ] {
        for decl in encoding_consts(lower) {
            bytes = bytes.constant(decl);
        }
    }

    let mut jni = JniGen::builder()
        // zenoh-flat's captured `#[prebindgen]` items — the single source of
        // this binding.
        .source(zenoh_flat::PREBINDGEN_OUT_DIR)
        .set_package_prefix("io.zenoh.jni") // base package of the generated JNI bindings
        // Every generated native call routes through `JNINative`; trigger our own
        // loader from its static initializer so the native library is loaded
        // transparently before any extern resolves (consumers never load it).
        .set_jni_native_init("io.zenoh.jni.NativeLibrary.ensureLoaded()")
        // De-prefix class-method names (`keyexpr_as_str` -> `asStr`): the
        // generator's default method mangle is identity, so restore the
        // namespace-relative naming this binding's Kotlin API expects.
        .set_method_name_mangle(|_, class, n| strip_flat_class_prefix(class, n))
        // ── Duration (advanced pub/sub option periods) ────────────────────
        // The semantic `Duration` crosses as bounded `u64` milliseconds
        // (see src/duration.rs). Reserving `u64::MAX` as the invalid
        // representation gives `Option<Duration>` a niche, so it crosses as a
        // raw `Long` (no boxed-`Long`/`JObject`); a duration exceeding `u64`
        // milliseconds is rejected to the binding error channel.
        .convert(
            convert!(Duration)
                .input(fun!(crate::duration_from_millis).sig(sig!((ms: u64) -> Duration)))
                .output(
                    fun!(crate::duration_to_millis).sig(sig!((d: Duration) -> Result<u64, String>)),
                )
                .valid_range(0u64..=u64::MAX - 1),
        )
        // ── Errors ────────────────────────────────────────────────────────
        // `Error` is the `E` of every fallible `Result<_, Error>` — a
        // RUST-SIDE-ONLY type: no class declaration, so no Kotlin `Error`
        // class exists and the value never crosses. Its canonical output is
        // the message string (1 leaf), auto-applied to the `E` position of
        // every such `Result` — i.e. the `onError` callback's argument. The
        // field name is explicit (no class member to inherit from).
        .expand(expand_return!(Error).field(fun!(error_get_message).name("message")))
        // ── Key expressions ──────────────────────────────────────────────
        // Canonical input: a key-expr param accepts EITHER a String (built via
        // `keyexpr_new_try_from`) OR an existing handle (identity), selector-
        // dispatched. Canonical output: the handle only (identity, 1 raw jlong);
        // the string form stays an on-demand accessor method (`getStr`).
        .package(
            package!("keyexpr")
                .class(
                    ptr_class!(KeyExpr)
                        // `.gc_managed()`: with the string-only receive path,
                        // KeyExpr handles exist only on cold paths — declared
                        // keyexprs (long-lived, user may forget to undeclare),
                        // construction/algebra probes (closed immediately),
                        // declare-time clones (consumed) — so the Cleaner
                        // backstop costs nothing per message and declared
                        // handles stop leaking on forgotten close.
                        .gc_managed()
                        // Read accessors → instance methods on the KeyExpr class.
                        // `newClone` returns the borrowed clone as a WHOLE handle —
                        // override the class's default return fields (identity via
                        // the owned converter) so the borrowed-opaque clone path
                        // applies instead.
                        .method(fun!(keyexpr_as_str))
                        .method(
                            fun!(keyexpr_new_clone)
                                .expand_return(expand_return!(KeyExpr).field_self()),
                        )
                        .method(fun!(keyexpr_to_string).name("toStr"))
                        // Constructors → companion factories returning `Result<KeyExpr, Error>`;
                        // `tryFrom` is also the build-from-String input variant
                        // (see `expand_param!(KeyExpr)` below).
                        .constructor(fun!(keyexpr_new_try_from))
                        .constructor(fun!(keyexpr_new_autocanonize))
                        // `a` is a `&KeyExpr` (string-or-handle); split it so
                        // `join(a: KeyExpr, b: String)` is an idiomatic overload.
                        .constructor(fun!(keyexpr_new_join).split_on_param("a"))
                        .constructor(fun!(keyexpr_new_concat).split_on_param("a"))
                        // Consumer methods: the receiver key-expr is `this`; the other
                        // param accepts a String (built via the default param variants below).
                        .method(fun!(keyexpr_intersects).split_on_param("b"))
                        .method(fun!(keyexpr_includes).split_on_param("b"))
                        .method(fun!(keyexpr_relation_to).split_on_param("b")),
                )
                .class(enum_class!(SetIntersectionLevel)),
        )
        // Default param variants: a key-expr param accepts EITHER a String (built
        // via `tryFrom`) OR an existing handle (self). Default return field: the
        // STRING — deliberately inverting forward-extraction for this type.
        // A received keyexpr never carries a wire declaration (zenoh's RX path
        // builds it declaration-less), so its native handle buys nothing over
        // the string on re-send, consumers almost always read the string
        // anyway, and delivering the handle cost a per-message native
        // allocation with no owner to free it. One eager jstring instead:
        // nothing to free, no second `getStr` crossing. Handles exist only
        // where the wire declaration does — `session_declare_keyexpr` (and
        // explicit `newClone`), which return raw handles as constructors.
        .expand(
            expand_param!(KeyExpr)
                .variant(fun!(keyexpr_new_try_from))
                .variant_self(),
        )
        .expand(expand_return!(KeyExpr).field(fun!(keyexpr_as_str)))
        // ── Config + ZenohId ──────────────────────────────────────────────
        .package(
            package!("config")
                .class(
                    ptr_class!(Config)
                        // `.gc_managed()`: cold-path leak backstop. A config
                        // is normally consumed by `open`/`scout`, so a live
                        // handle exists only between construction and use —
                        // GC only ever fires for configs that were built and
                        // then abandoned. With this, ZBytes is the SOLE
                        // deliberate gc_managed exclusion (hot-path cost).
                        .gc_managed()
                        .method(fun!(config_get_json))
                        .method(fun!(config_new_clone))
                        // `config.insertJson5(...)` — receiver-style mutator.
                        .method(fun!(config_insert_json5))
                        // Factories → Config companion-object members.
                        .constructor(fun!(config_new_default))
                        .constructor(fun!(config_new_from_file))
                        .constructor(fun!(config_new_from_json5))
                        .constructor(fun!(config_new_from_yaml)),
                )
                .class(enum_class!(WhatAmI))
                // `ZenohId` is a plain value: one fixed-width
                // (`ZENOH_ID_MAX_SIZE`) byte field, which crosses as a Kotlin
                // `ByteArray` — no closeable handle, and no raw-memory image of
                // the Rust struct. The field IS the class's `bytes` property, so
                // no separate accessor is needed. `Vec<ZenohId>` (session
                // peers/routers) folds each element WHOLE as the typed class.
                // Its read accessors become instance methods.
                .class(data_class!(ZenohId).method(fun!(zenoh_id_to_string).name("toStr"))),
        )
        // ── Scouting ──────────────────────────────────────────────────────
        // Canonical output: the scout callback decomposes a `Hello` into its
        // three read fields in ONE crossing (no handle — read-only). Auto-applies
        // to `scout`'s `Fn(Hello)`.
        .package(
            package!("scouting")
                .class(
                    ptr_class!(Hello)
                        .method(fun!(hello_get_whatami)) // WhatAmI enum -> Int
                        .method(fun!(hello_get_zid)) // ZenohId data class -> ByteArray
                        .method(fun!(hello_get_locators)), // Vec<String> -> List<String>
                )
                // Semantic resources (Scout, and Session/Publisher/… below)
                // are `.gc_managed()` as a LEAK BACKSTOP: explicit
                // close/undeclare stays the primary path (it settles the
                // release ticket), and the shared Cleaner only frees a
                // resource whose owner forgot — replacing the SDKs'
                // deprecated-for-removal `finalize()` nets (JEP 421).
                .class(ptr_class!(Scout).gc_managed())
                .fun(fun!(scout)),
        )
        .expand(
            expand_return!(Hello)
                .field(fun!(hello_get_whatami))
                .field(fun!(hello_get_zid))
                .field(fun!(hello_get_locators)),
        )
        // ── Logger ────────────────────────────────────────────────────────
        .package(
            package!("logger")
                .fun(fun!(init_android_logs))
                .fun(fun!(try_init_zenoh_logs_from_env))
                .fun(fun!(init_zenoh_logs_from_env_or)),
        )
        // ── QoS enums ─────────────────────────────────────────────────────
        .package(
            package!("qos")
                .class(enum_class!(Reliability))
                .class(enum_class!(Priority))
                .class(enum_class!(CongestionControl)),
        )
        // ── Bytes: ZBytes + Encoding (declared above) ─────────────────────
        .package(bytes)
        // ZBytes default input: built from a `ByteArray` via `fromVec`.
        // Default output: the handle only — the bytes are heavy and fetched
        // on demand via `zbytesAsBytes` (one borrow-copy).
        .expand(expand_param!(ZBytes).variant(fun!(zbytes_new_from_vec)))
        .expand(expand_return!(ZBytes).field_self())
        // Encoding INPUT: value or handle, minimizing JNI crossings. An
        // encoding IS its decomposed `(id, schema?)` pair (Zenoh core
        // semantics — the string form is derived from a fixed table), so the
        // default build arm crosses those cheap primitives via `fromId`
        // INSIDE the send call itself: a predefined encoding costs one Int,
        // no handle, no extra crossing. The `variant_self()` arm additionally
        // accepts an existing handle for encodings that already own one
        // (custom-created, born at construction) — a bare jlong instead of
        // re-decoding the schema string each call; every encoding param is
        // `Option<&Encoding>`, so the handle is borrowed and reusable
        // forever. No `.split_on_param`: neither arm alone covers the
        // value-or-handle send dichotomy, so consumers drive the selector
        // block directly.
        // OUTPUT: the `(id, schema?)` value leaves only — received encodings
        // never carry a native handle. A ping-pong A/B (the very
        // receive-then-resend scenario that motivated handle delivery) showed
        // the per-receive handle lifecycle (clone + Box + wrapper + Cleaner)
        // costs more than the schema-string re-decode it saves on the resent
        // fraction, so the handle arm of the send selector is served only by
        // construction-born handles (`variant_self()` above).
        .expand(
            expand_param!(Encoding)
                .variant(fun!(encoding_new_from_id))
                .variant_self(),
        )
        .expand(
            expand_return!(Encoding)
                .field(fun!(encoding_get_id))
                .field(fun!(encoding_get_schema)),
        )
        // ── Time ──────────────────────────────────────────────────────────
        // A timestamp is a plain VALUE in zenoh-flat (NTP64 component + the
        // originating node id), so it crosses as a flat data class — its fields
        // become decoupled leaves, and nested in a `Sample` it contributes those
        // leaves directly (no handle, no accessor crossing).
        // `TimestampStack` is path instrumentation — a debugging aid, `None`
        // unless zenoh recorded any, and read on the rare occasion someone asks.
        // So it stays a HANDLE with its own accessors rather than joining the
        // bulk decompositions that carry it: fetching it costs an extra crossing
        // (`sample.timestampStack()`, then `records()`), which is the right
        // trade for a field almost every delivery would otherwise pay for.
        // Its records materialize only on that second call.
        .package(
            package!("time")
                .class(data_class!(Timestamp))
                .class(enum_class!(InterceptionPoint))
                // A record's timestamp is zenoh's own clock OR application
                // bytes, never both — a genuine sum, so a sealed interface.
                .class(sealed_class!(InstrumentationTimestamp))
                .class(data_class!(TimestampInstrumentation))
                .class(data_class!(TimestampStackRecord))
                .class(
                    ptr_class!(TimestampStack)
                        .gc_managed()
                        .method(fun!(timestamp_stack_get_instrumentation))
                        .method(fun!(timestamp_stack_get_records)),
                ),
        )
        // ── Sample ────────────────────────────────────────────────────────
        // Canonical INPUT: identity only — a `Sample` param takes the owned
        // handle directly. (The full-options constructors carry `Option<ptr_class>`
        // params the recursive-input fold can't build through, so a `Sample` is
        // built via the `sample_new_*` constructors below and consumed by handle.)
        // Canonical OUTPUT: the full sample decomposed in ONE crossing. Each record
        // is unwrapped per its return type's own canonical output (key_expr ->
        // handle+String, payload/attachment -> ByteArray, encoding -> String,
        // timestamp -> the `Timestamp` value's leaves, kind/priority/congestion/
        // reliability -> Int, express -> Boolean, source_info -> the `SourceInfo`
        // value's leaves). Auto-applies to every (non-Result) `Sample` return.
        .package(
            package!("sample")
                .class(enum_class!(SampleKind))
                // Source information is a plain VALUE (`SourceInfo`, nesting
                // `EntityGlobalId` / `ZenohId`), optional as a whole: a sample
                // either carries all of it or none of it.
                .class(data_class!(SourceInfo))
                .class(
                    ptr_class!(Sample)
                        // All sample getters are record sources AND instance methods on
                        // the Sample class; decomposition happens via the canonical
                        // output below.
                        // The handle's own accessors hand back handles: a
                        // caller who already holds the Sample wants the nested
                        // value, not its decomposition (which is what the
                        // canonical output below is for).
                        .method(
                            fun!(sample_get_key_expr)
                                .expand_return(expand_return!(KeyExpr).field_self()),
                        )
                        .method(fun!(sample_get_payload))
                        .method(
                            fun!(sample_get_encoding)
                                .expand_return(expand_return!(Encoding).field_self()),
                        )
                        .method(fun!(sample_get_kind))
                        .method(fun!(sample_get_timestamp))
                        .method(fun!(sample_get_express))
                        .method(fun!(sample_get_priority))
                        .method(fun!(sample_get_congestion_control))
                        .method(fun!(sample_get_attachment))
                        .method(fun!(sample_get_reliability))
                        .method(fun!(sample_get_source_info))
                        // Fetched on demand — see the `time` package above.
                        .method(fun!(sample_get_timestamp_stack)),
                )
                // Standalone sample constructors (callable from Kotlin); consumed by handle.
                .fun(fun!(sample_new_put))
                .fun(fun!(sample_new_delete)),
        )
        // Identity-only input: exactly the default (documented no-op).
        .expand(expand_param!(Sample).variant_self())
        // Full-sample decomposition, taken from `SampleStruct` so the field list
        // cannot drift from zenoh-flat's own value form. The CONSUMING form:
        // every delivery position for a sample is owned (`impl Fn(Sample)`
        // callbacks, owned returns), so the fields MOVE out instead of being
        // cloned one by one out of a value that is about to be dropped.
        // `timestamp_stack` is held OUT of it (an override stating no leaves).
        // It is debugging instrumentation, `None` unless zenoh recorded any, so
        // a slot on every sample would be paid for by every delivery to serve
        // almost none. It is reachable instead through `sample.timestampStack()`
        // — one extra crossing, on the rare call that wants it.
        .expand(expand_return!(Sample).fields_self_into(
            fields!(sample_into_struct).field("timestamp_stack", expand_return!(TimestampStack)),
        ))
        // ── Pub/Sub ───────────────────────────────────────────────────────
        // key_expr / payload / attachment / encoding params are auto-constructed
        // by their types' canonical inputs (no per-fn calls).
        .package(
            package!("pubsub")
                // `publisher.put(...)` / `publisher.delete(...)` — receiver-style.
                .class(
                    ptr_class!(Publisher)
                        .gc_managed()
                        .method(fun!(publisher_put))
                        .method(fun!(publisher_delete)),
                )
                .class(ptr_class!(Subscriber).gc_managed())
                // ── Advanced pub/sub (unstable) ───────────────────────────
                // Option structs as FLAT data classes: fields cross as decoupled
                // leaves (reassembled via a generated `fromParts`). `RepliesConfig`
                // is declared before `CacheConfig`, which nests it as a field;
                // `Duration` fields ride the `convert!(Duration)` domain above.
                .class(data_class!(MissDetectionConfig))
                .class(data_class!(RepliesConfig))
                .class(data_class!(CacheConfig))
                .class(data_class!(HistoryConfig))
                // `RecoveryMode` is a data-carrying enum (the modes are mutually
                // exclusive by construction upstream), so it mirrors as a Kotlin
                // sealed interface; it is declared before `RecoveryConfig`, whose
                // `mode` field nests it.
                .class(sealed_class!(RecoveryMode))
                .class(data_class!(RecoveryConfig))
                // `EntityGlobalId` is declared before `Miss`, which nests it as a
                // field; its `zid` is the `ZenohId` data class declared above.
                .class(data_class!(EntityGlobalId))
                .class(data_class!(Miss))
                // Advanced publisher: put/delete + matching status/listeners
                // (the matching callback delivers a plain `bool`).
                .class(
                    ptr_class!(AdvancedPublisher)
                        .gc_managed()
                        .method(fun!(advanced_publisher_put))
                        .method(fun!(advanced_publisher_delete))
                        .method(fun!(advanced_publisher_matching_status))
                        .method(fun!(advanced_publisher_declare_matching_listener))
                        .method(fun!(
                            advanced_publisher_declare_background_matching_listener
                        )),
                )
                .class(ptr_class!(MatchingListener).gc_managed())
                // Advanced subscriber: sample-miss listeners (callback delivers a
                // `Miss` data class) + detect-publishers subscribers.
                .class(
                    ptr_class!(AdvancedSubscriber)
                        .gc_managed()
                        .method(fun!(advanced_subscriber_declare_sample_miss_listener))
                        .method(fun!(
                            advanced_subscriber_declare_background_sample_miss_listener
                        ))
                        .method(fun!(
                            advanced_subscriber_declare_detect_publishers_subscriber
                        ))
                        .method(fun!(
                            advanced_subscriber_declare_background_detect_publishers_subscriber
                        )),
                )
                .class(ptr_class!(SampleMissListener).gc_managed()),
        )
        // ── Test-only correspondence oracle ───────────────────────────────
        // The zenoh-flat parameters-processing API, exposed in a dedicated
        // `io.zenoh.jni.test` package that the SDKs (zenoh-java, zenoh-kotlin)
        // are NOT meant to import. Their production path uses the pure-Kotlin
        // `io.zenoh.jni.query.Parameters` instead — crossing JNI per string
        // operation is expensive, a JNI peculiarity rather than a zenoh-flat
        // design choice. These native functions exist solely so this crate's
        // own `ParametersCorrespondenceTest` can verify the pure implementation
        // against the real zenoh-flat semantics.
        .package(
            package!("test")
                .fun(fun!(parameters_get))
                .fun(fun!(parameters_values))
                .fun(fun!(parameters_contains_key))
                .fun(fun!(parameters_insert))
                .fun(fun!(parameters_remove))
                .fun(fun!(parameters_extend))
                .fun(fun!(parameters_is_well_formed)),
        )
        // ── Query / Queryable / Querier ───────────────────────────────────
        .package(
            package!("query")
                .class(ptr_class!(Queryable).gc_managed())
                // `querier.get(...)` — receiver-style method on Querier.
                .class(ptr_class!(Querier).gc_managed().method(fun!(querier_get)))
                .class(enum_class!(ReplyKeyExpr))
                .class(enum_class!(QueryTarget))
                .class(enum_class!(ConsolidationMode))
                // A selector is a plain VALUE — the key expression selecting the
                // keys plus the parameters refining the selection — so it crosses
                // as a flat data class. Its `key_expr` is a nested handle-backed
                // type, so it is carried as a `KeyExpr` handle (a value form
                // decomposes exactly one level).
                .class(data_class!(Selector))
                .class(
                    ptr_class!(Query)
                        // gc_managed: an abandoned Query's backstop close also
                        // finalizes the reply stream (same as the SDKs' former
                        // finalize()), so the querier's get completes.
                        .gc_managed()
                        .method(fun!(query_get_key_expr))
                        .method(fun!(query_get_parameters))
                        .method(fun!(query_get_payload))
                        .method(fun!(query_get_encoding))
                        .method(fun!(query_get_attachment))
                        .method(fun!(query_get_accepts_replies))
                        // Reply ops on the owned/borrowed query handle →
                        // `query.replySuccess(...)` / `replyError` / `replyDelete`.
                        .method(fun!(query_reply_success).split_on_param("key_expr"))
                        .method(fun!(query_reply_error))
                        .method(fun!(query_reply_delete).split_on_param("key_expr"))
                        // `query_reply_sample` takes the sample by owned handle
                        // (Sample's canonical input is identity).
                        .method(fun!(query_reply_sample)),
                )
                // ── Reply / ReplyError ────────────────────────────────────────
                .class(
                    ptr_class!(ReplyError)
                        .method(fun!(reply_error_get_payload))
                        .method(fun!(reply_error_get_encoding))
                        // On demand, like a sample's — and likewise absent from
                        // the canonical output below.
                        .method(fun!(reply_error_get_timestamp_stack)),
                )
                .class(
                    ptr_class!(Reply)
                        // `reply.sample()` / `reply.err()` on a held handle are
                        // the cloned-handle form — the decomposition lives in
                        // the canonical output below.
                        .method(fun!(reply_get_replier_id))
                        .method(fun!(reply_is_ok))
                        .method(
                            fun!(reply_get_sample)
                                .expand_return(expand_return!(Sample).field_self()),
                        )
                        .method(
                            fun!(reply_get_err)
                                .expand_return(expand_return!(ReplyError).field_self()),
                        ),
                ),
        )
        // Canonical output: the queryable callback decomposes a `Query` into
        // BOTH its read fields AND the owned handle (identity) in ONE crossing
        // — keeping the handle lets the consumer reply (`query_reply_*`) after
        // the callback returns; a query must outlive its callback to be
        // answered. `.field_self()` is declared LAST: the root identity moves
        // the owned query while the nested KeyExpr identity (from
        // `query_get_key_expr`) clones from a borrow of it — the generator
        // hard-errors on the reverse order.
        .expand(
            expand_return!(Query)
                .field(fun!(query_get_key_expr))
                .field(fun!(query_get_parameters))
                .field(fun!(query_get_payload))
                .field(fun!(query_get_encoding))
                .field(fun!(query_get_attachment))
                .field(fun!(query_get_accepts_replies))
                .field_self(),
        )
        // ReplyError canonical output: a failed reply's error decomposed in
        // one crossing — payload -> ByteArray, encoding -> String.
        .expand(
            expand_return!(ReplyError)
                .field(fun!(reply_error_get_payload))
                .field(fun!(reply_error_get_encoding)),
        )
        // Reply canonical output: the whole reply decomposed in ONE crossing
        // (PRODUCT model — both arms' leaves always present, the not-taken
        // arm's are null). The replier's `EntityGlobalId` (a value: zid + eid)
        // + the is_ok discriminator, then the
        // ok arm splices the full sample and the err arm splices
        // payload/encoding. Auto-applies to the `Fn(Reply)` callbacks of
        // `session_get` / `querier_get` / liveliness get; no identity record,
        // so no `Reply` handle crosses.
        .expand(
            expand_return!(Reply)
                .field(fun!(reply_get_replier_id))
                .field(fun!(reply_is_ok))
                .field(fun!(reply_get_sample))
                .field(fun!(reply_get_err)),
        )
        // ── Liveliness + Session ──────────────────────────────────────────
        // `LivelinessToken` is just an opaque handle; the liveliness operations
        // (`liveliness_*`) are declared under the `session` package below,
        // alongside the session API they extend.
        .package(package!("liveliness").class(ptr_class!(LivelinessToken).gc_managed()))
        .package(
            package!("session").class(
                // Every session operation is a RECEIVER-STYLE instance method on
                // `Session` (its `&Session` first param binds to `this`), so the
                // Kotlin surface reads `session.put(...)` / `session.declarePublisher(...)`.
                // `open` has no receiver (it creates a Session) → companion factory.
                ptr_class!(Session)
                    .gc_managed()
                    .constructor(fun!(open))
                    .method(fun!(session_get_zid))
                    .method(fun!(session_declare_publisher).split_on_param("key_expr"))
                    .method(fun!(session_declare_advanced_publisher).split_on_param("key_expr"))
                    .method(fun!(session_put).split_on_param("key_expr"))
                    .method(fun!(session_delete).split_on_param("key_expr"))
                    .method(fun!(session_declare_subscriber).split_on_param("key_expr"))
                    .method(fun!(session_declare_advanced_subscriber).split_on_param("key_expr"))
                    .method(fun!(session_declare_querier).split_on_param("key_expr"))
                    .method(fun!(session_declare_queryable).split_on_param("key_expr"))
                    .method(fun!(session_declare_keyexpr))
                    // Undeclaring needs the declared handle, not a string — opt its
                    // key_expr param out of the (String-building) default param variants.
                    .method(
                        fun!(session_undeclare_keyexpr)
                            .expand_param("key_expr", expand_param!(KeyExpr).variant_self()),
                    )
                    // `session.get(...)` takes a whole `Selector` value (key expr
                    // + parameters), so there is no key-expr param to split on.
                    .method(fun!(session_get))
                    // `Vec<ZenohId>`: ZenohId is a data class, so these return
                    // `List<ZenohId>` via the normal Vec converter. Named to drop
                    // the `get` prefix (`peersZid` / `routersZid`).
                    .method(fun!(session_get_peers_zid))
                    .method(fun!(session_get_routers_zid))
                    // Liveliness ops also take `&Session` first → Session methods.
                    .method(fun!(liveliness_declare_token).split_on_param("key_expr"))
                    .method(fun!(liveliness_get).split_on_param("key_expr"))
                    .method(fun!(liveliness_declare_subscriber).split_on_param("key_expr")),
            ),
        );

    // zenoh-flat's `encoding_const_*` `&'static Encoding` loaning factories
    // are superseded here by the `ENCODING_*` consts above — acknowledge the
    // whole naming family so the generator doesn't warn about undeclared
    // functions.
    jni = jni.ignore(matching(|name| name.starts_with("encoding_const_")));

    // The remaining flat functions are intentionally outside this binding
    // surface. Handle classes already provide close/take lifecycle operations;
    // the SDK retains its declared key expression/entity metadata; and the Vec
    // ZBytes constructor is the canonical ByteArray input path. Acknowledge the
    // exclusions explicitly so newly added source functions remain visible as
    // generation warnings instead of being lost in a standing warning list.
    for name in [
        // Advanced pub/sub lifecycle/metadata: the gc_managed handle's close
        // (drop = undeclare) covers these, as for the regular pub/sub fns.
        "advanced_publisher_get_id",
        "advanced_publisher_get_key_expr",
        "advanced_publisher_undeclare",
        "advanced_subscriber_get_id",
        "advanced_subscriber_get_key_expr",
        "advanced_subscriber_undeclare",
        "matching_listener_undeclare",
        "sample_miss_listener_undeclare",
        "liveliness_undeclare_token",
        "publisher_get_id",
        "publisher_get_key_expr",
        "publisher_undeclare",
        "querier_get_id",
        "querier_get_key_expr",
        "querier_undeclare",
        "queryable_get_id",
        "queryable_get_key_expr",
        "queryable_undeclare",
        "session_close",
        "session_is_closed",
        "session_new_timestamp",
        "subscriber_get_id",
        "subscriber_get_key_expr",
        "subscriber_undeclare",
        "zbytes_new_from_slice",
    ] {
        jni = jni.ignore(FunctionDecl::new(syn::parse_str(name).unwrap()));
    }

    // ── Outputs ───────────────────────────────────────────────────────────
    // Resolve the declared surface against the source items and write both
    // generated artifacts.
    let generation = match jni.build() {
        Ok(generation) => generation,
        Err(err) => fail("build failed", err),
    };

    // Rust bindings → src/generated_bindings.rs. Absolute path so the file lands
    // in the source tree (committed to git and included by `src/lib.rs`).
    let crate_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    let rust_dest = std::path::Path::new(&crate_dir)
        .join("src")
        .join("generated_bindings.rs");
    let rust_path = match generation.write_rust(&rust_dest) {
        Ok(path) => path,
        Err(err) => fail("write_rust failed", err),
    };
    println!(
        "cargo:warning=Generated bindings at: {}",
        rust_path.display()
    );
    patch_class_cache(&rust_path);

    // ── Kotlin bindings → kotlin/generated/ ─────────────────────────────
    // The runtime module's Gradle source set picks these up via
    // `kotlin.srcDir("$rootDir/zenoh-flat-jni/kotlin/generated")`.
    let kotlin_root = std::path::Path::new(&crate_dir)
        .join("kotlin")
        .join("generated");
    // The root is generator-owned: `write_kotlin` deletes and recreates it,
    // so no consumer-side cleanup is needed.
    for path in match generation.write_kotlin(&kotlin_root) {
        Ok(paths) => paths,
        Err(err) => fail("write_kotlin failed", err),
    } {
        println!("cargo:warning=Wrote {}", path.display());
    }

    // The resolved-surface report: committed next to the regen so a decl's
    // effect is reviewable without reading generated Kotlin.
    if let Err(err) = std::fs::write(
        std::path::Path::new(&crate_dir)
            .join("kotlin")
            .join("REPORT.md"),
        generation.report(),
    ) {
        fail("write REPORT.md failed", err);
    }
}
