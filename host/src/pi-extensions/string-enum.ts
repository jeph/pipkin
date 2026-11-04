import { type TUnsafe, Type } from "typebox";

/**
 * A `{ type: "string", enum }` schema, the form Google's API takes where it rejects an `anyOf` of literals. pi-ai's
 * own `StringEnum` returns a pi-schema `Schema` since 1.5.0, which TypeBox's `Type.Optional`, `Static` and `Value`
 * don't treat as a TypeBox schema; this one is TypeBox's, as Pi's was before.
 */
export function StringEnum<const T extends readonly string[]>(
  values: T,
  options?: { description?: string },
): TUnsafe<T[number]> {
  return Type.Unsafe<T[number]>({ type: "string", enum: values, ...options });
}
