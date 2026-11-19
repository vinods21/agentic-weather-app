// The OpenAPI spec, imported by relative path from its home in the backend (ADR 0018 section 4).
// It is a JSON module import (no copy in ui/, no Node fs).
import rawSpec from '../../../src/main/resources/openapi/openapi-v1.json';

export interface SpecProperty {
  $ref?: string;
  type?: string;
  nullable?: boolean;
  enum?: string[];
  items?: SpecProperty;
}

export interface SpecSchema {
  type?: string;
  required?: string[];
  properties?: Record<string, SpecProperty>;
}

export interface SpecDocument {
  components: {
    schemas: Record<string, SpecSchema>;
    parameters: Record<string, { schema?: SpecProperty }>;
  };
}

export const spec = rawSpec as unknown as SpecDocument;

/** Reads the enum at a path below `components`: `schemas.<S>.<prop>` or `parameters.<P>`. */
export function enumAt(path: string): string[] | undefined {
  const [section, name, property] = path.split('.');
  if (section === 'parameters' && name) {
    return spec.components.parameters[name]?.schema?.enum;
  }
  if (section === 'schemas' && name && property) {
    return spec.components.schemas[name]?.properties?.[property]?.enum;
  }
  return undefined;
}

/** The last segment of a `#/components/schemas/X` reference. */
export function refName(ref: string | undefined): string | undefined {
  return ref?.split('/').pop();
}
