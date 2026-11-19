import { describe, expect, it } from 'vitest';
import { enumAt, refName, spec } from '../test/spec';
import { ENUM_CONTRACT, SCHEMA_CONTRACT } from './contract';

const AC37_SCHEMAS = [
  'CurrentWeather',
  'Forecast',
  'ForecastDay',
  'Location',
  'Condition',
  'Favourite',
  'FavouriteList',
  'FavouriteWeather',
  'FavouriteWeatherList',
  'FavouriteForecast',
  'FavouriteForecastList',
  'FavouriteWeatherError',
  'CreateFavouriteRequest',
  'UpdateFavouriteRequest',
  'ReorderFavouritesRequest',
  'Problem',
  'FieldError',
];

const sorted = (values: readonly string[]) => [...values].sort();

function specView(name: string) {
  const schema = spec.components.schemas[name];
  if (!schema) throw new Error(`Schema ${name} is missing from openapi-v1.json`);
  const properties = schema.properties ?? {};
  const required = schema.required ?? [];
  const refs: Record<string, string> = {};
  for (const [prop, def] of Object.entries(properties)) {
    const target = refName(def.$ref) ?? refName(def.items?.$ref);
    if (target) refs[prop] = target;
  }
  return {
    required: sorted(required),
    optional: sorted(Object.keys(properties).filter((p) => !required.includes(p))),
    nullable: sorted(Object.keys(properties).filter((p) => properties[p]?.nullable === true)),
    refs,
  };
}

describe('API contract drift (openapi-v1.json)', () => {
  it('AC-37: the manifest covers exactly the schemas the UI uses', () => {
    expect(sorted(Object.keys(SCHEMA_CONTRACT))).toEqual(sorted(AC37_SCHEMAS));
  });

  for (const [name, manifest] of Object.entries(SCHEMA_CONTRACT)) {
    it(`AC-37: ${name} matches the spec (required, optional, nullable, $ref targets)`, () => {
      const actual = specView(name);
      expect({ name, required: sorted(manifest.required) }).toEqual({ name, required: actual.required });
      expect({ name, optional: sorted(manifest.optional) }).toEqual({ name, optional: actual.optional });
      expect({ name, nullable: sorted(manifest.nullable) }).toEqual({ name, nullable: actual.nullable });
      expect({ name, refs: manifest.refs }).toEqual({ name, refs: actual.refs });
    });
  }

  for (const { at, values } of ENUM_CONTRACT) {
    it(`AC-37: enum ${at} matches the spec`, () => {
      const actual = enumAt(at);
      expect(actual, `enum ${at} not found in the spec`).toBeDefined();
      expect(sorted(values)).toEqual(sorted(actual ?? []));
    });
  }
});
