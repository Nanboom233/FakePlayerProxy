# Java Language Guidelines

## Multiple Return Values

When one operation returns two or three related values, first consider a
`Pair` or `Triple` type that the module already provides. Use `Pair` for two
values and `Triple` for three values. This keeps one simple return shape and
avoids an interface hierarchy used only as a value container.

An interface remains suitable when the alternatives have different contracts
or behavior.

### Wrong

```java
sealed interface DecodeResult permits Decoded, Missing {
}

record Decoded(PublicKey key, byte[] challenge) implements DecodeResult {
}

record Missing() implements DecodeResult {
}
```

This hierarchy only packages a small result. It adds types and control flow
without adding a distinct behavior contract.

### Preferred

```java
Optional<Pair<PublicKey, byte[]>> decodeTargetData(PublicKey proxyKey) {
    // Return an empty value when target data is not available.
}
```

The `Optional` represents availability. The `Pair` contains the two related
values. For three values, use the module's existing `Triple` type.

This is a design preference, not a dedicated test requirement. Review the type
shape when the code changes.

## Method Extraction

Extract a method only when at least one of these current conditions applies:

1. The body is large enough that extraction makes the current flow easier to
   read.
2. The method has an existing reuse requirement.

Do not extract a method for possible future reuse. Do not split one semantic
operation into a one-use wrapper and helper when reading both methods is harder
than reading the operation inline.

### Wrong

```java
private void continuePreparedLogin(PreparedLogin preparedLogin, boolean allow) {
    var choice = allow
            ? preparedLogin.relayChoice()
            : preparedLogin.vanillaChoice();
    continueLogin(choice, preparedLogin);
}

private void continueLogin(
        Pair<String, ServerboundKeyPacket> choice,
        PreparedLogin preparedLogin) {
    // Complete the same one-shot login operation.
}
```

The second method has one caller and no separate semantic responsibility. The
split interrupts one login flow without adding reuse or readability.

### Preferred

```java
private void continuePreparedLogin(PreparedLogin preparedLogin, boolean allow) {
    var choice = allow
            ? preparedLogin.relayChoice()
            : preparedLogin.vanillaChoice();
    // Complete the selected one-shot login operation here.
}
```

Keep the operation together until its size harms readability or a real reuse
requirement exists. This convention does not create a dedicated test
requirement.

## Annotation Ownership

Add a new contract annotation only when this project owns the declaration and
can guarantee the contract. Mod-owned helpers, records, and constructors can
use `@NotNull` when every project-owned caller provides a non-null value. An
override is different: it must preserve the effective contract inherited from
the foreign declaration.

Do not add `@NotNull` to foreign inputs. This includes `@Shadow` fields, Mixin
injector parameters, `@Local` captures, parameters supplied by an overridden
Minecraft method, and override parameters whose target contract is nullable or
unspecified. An annotation on these declarations cannot strengthen the contract
that Minecraft provides. Handle a possible null at the project boundary when
the foreign API does not guarantee a value.

Add a framework annotation when the framework contract requires it. A `@Shadow`
of a final target field must also use Mixin's `@Final`, even when the Mixin only
reads the field. Do not add `@Final` when the target member is not final.

Before adding `@Shadow`, use an existing public API when it represents the same
owner and lifecycle. For example, use `Minecraft.getInstance()` instead of
shadowing the target class's `minecraft` field. Keep a shadow only when the
Mixin must access a target member that has no suitable public entry point.

When a shadow method's target parameter names are available, keep the same names
in the shadow declaration. This makes the binding readable and avoids Mixin
inspection failures caused by a mismatched parameter name.

### Wrong

```java
@Shadow @Final private @NotNull Minecraft minecraft;
@Shadow private Connection connection;

@Shadow
private void setEncryption(ServerboundKeyPacket keyPacket, Cipher decrypt, Cipher encrypt) {
}

private void prepare(
        @NotNull ClientboundHelloPacket packet,
        @Local @NotNull SecretKey secretKey) {
}
```

These values belong to Minecraft. The Mixin does not own their declarations or
their null contracts.

### Preferred

```java
@Shadow @Final private Connection connection;

@Shadow
private void setEncryption(
        ServerboundKeyPacket setKeyPacket,
        Cipher decryptCipher,
        Cipher encryptCipher) {
}

private void prepare(
        ClientboundHelloPacket packet,
        @Local SecretKey secretKey) {
    var minecraft = Minecraft.getInstance();
}

private void continueLogin(@NotNull PreparedLogin preparedLogin) {
}
```

The public accessor replaces the unnecessary `Minecraft` shadow. The remaining
shadow has no equivalent public entry point and mirrors the target field's final
modifier. The shadow method keeps the target parameter name. The injected
values keep Minecraft's contract. `PreparedLogin` uses `@NotNull` because it is
a project-owned input with project-owned callers.

This convention does not require dedicated tests. Compiler, framework, and code
review feedback are sufficient unless an annotation changes runtime behavior.

## Override Contract Fidelity

An override of a foreign method must preserve the effective contract declared
by the target version. Inspect method annotations, external annotations, and
package or type defaults such as JSpecify `@NullMarked`. Looking only at the
method text is insufficient because a type without a local annotation can still
have an effective non-null contract.

`@Override` confirms which foreign method is implemented, but Java does not
carry a foreign package's default annotation into the overriding package. Spell
the inherited contract explicitly when the project package has a different
default. Adding or omitting nullability without checking that effective target
contract is annotation misuse.

### Wrong

```java
// ConfirmScreen is in a @NullMarked package, so this loses its return contract.
@Override
public Component getNarrationMessage() {
    return narration;
}
```

### Preferred

```java
@Override
public @NotNull Component getNarrationMessage() {
    return narration;
}
```

The explicit annotation preserves the effective non-null return contract after
the override moves into an unmarked project package. Verify the target method
and its package defaults through source or IDE inspection. This rule does not
create a dedicated test requirement.

## Mixin Local Capture

Use a target local-variable name as the first choice for MixinExtras `@Local`.
The name shows which target value the handler receives and makes the injection
easier to read than an ordinal or slot index.

### Wrong

```java
@Local(ordinal = 0) Cipher decryptCipher,
@Local(ordinal = 1) Cipher encryptCipher
```

The reader must inspect the target local-variable order to understand these
parameters.

### Preferred

```java
@Local(name = "decryptCipher") Cipher decryptCipher,
@Local(name = "encryptCipher") Cipher encryptCipher
```

Use an ordinal or index only when the target has no usable local-variable name.
This preference does not require a dedicated test. Mixin compilation and IDE
inspection validate the selected local.

## User-Facing Text

Use i18n for every text value that the user can see. This includes screens,
buttons, chat messages, status messages, errors, and disconnect reasons. Put the
text in the applicable language resource files and construct the component from
its translation key.

Debug-only displays and log messages do not require i18n because they are
diagnostic output, not user-interface text.

### Wrong

```java
connection.disconnect(Component.literal(
        "Unable to continue the proxy connection. Please try again."));
```

The disconnect reason is visible to the user, so a literal string violates the
i18n contract.

### Preferred

```java
connection.disconnect(Component.translatable(
        "fakeplayerproxy.disconnect.proxy_connection_failed"));
```

Define the key in each supported language resource. This convention does not
create a dedicated test requirement.

## Diagnostic Messages

Write log and debug messages that identify the failed operation, the affected
protocol field or component, and the concrete failure condition. Words such as
`invalid`, `error`, or `failed` are not sufficient when they do not state what
was checked or why the operation could not continue.

Include safe expected and observed values when they explain the failure. Keep
the user-facing message concise and translated. Keep the technical reason in
the log or debug output.

Pass a `Throwable` to the logger only when a real exception caused the failure.
If validation fails without an exception, log the concrete validation reason.
Do not pass `null` as a placeholder to a parameter that exists to preserve an
exception, stack trace, and cause chain.

### Wrong

```java
failConsent("Invalid FakePlayerProxy Server Hello envelope", null);
```

This message does not identify the failing envelope field or condition. The
`null` argument also provides no diagnostic detail and has no exception meaning.

### Preferred: Validation Failure

```java
LOGGER.error(
        "Cannot construct FPPACK response: challenge payload is {} bytes, "
                + "but RSA-1024 accepts at most {} bytes",
        payloadLength,
        maxPayloadLength);
connection.disconnect(Component.translatable(
        "fakeplayerproxy.disconnect.proxy_connection_failed"));
```

### Preferred: Exception Failure

```java
catch (CryptException exception) {
    LOGGER.error(
            "Cannot decode the target RSA public key from the Server Hello carrier",
            exception);
    connection.disconnect(Component.translatable(
            "fakeplayerproxy.disconnect.proxy_connection_failed"));
}
```

The validation message states the failed bound. The exception message states
the failed protocol stage and preserves the complete exception. This convention
does not create a dedicated test requirement.
