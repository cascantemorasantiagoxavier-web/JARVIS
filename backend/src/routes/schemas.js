// Esquemas de validación de entrada de todas las rutas.
import { z } from 'zod';

const voice = z.string().regex(/^[a-z0-9_-]{2,40}$/i).optional();

export const RealtimeSessionBody = z.object({
  model: z.string().max(80).optional(),
  voice,
  speed: z.number().min(0.25).max(1.5).optional(),
  instructions: z.string().max(12000).optional(),
});

export const RealtimeCallBody = RealtimeSessionBody.extend({
  sdp: z.string().min(10).max(20000),
  maxSessionMinutes: z.number().int().min(1).max(60).optional(),
});

export const CallIdParams = z.object({ callId: z.string().regex(/^[A-Za-z0-9_-]{4,128}$/) });

const jsonSchemaObject = z.record(z.string(), z.unknown());

export const ChatBody = z
  .object({
    instructions: z.string().max(20000).optional(),
    input: z.array(z.record(z.string(), z.unknown())).min(1).max(80),
    tools: z
      .array(
        z.object({
          name: z.string().regex(/^[a-zA-Z0-9_]{1,64}$/),
          description: z.string().max(1200),
          parameters: jsonSchemaObject,
        }),
      )
      .max(40)
      .optional(),
    mode: z.enum(['FAST', 'BALANCED', 'DEEP']).optional(),
  })
  .refine((b) => JSON.stringify(b).length <= 400_000, { message: 'La conversación enviada es demasiado grande.' });

const domain = z.string().regex(/^[a-z0-9.-]+\.[a-z]{2,}$/i);

export const WebSearchBody = z.object({
  query: z.string().trim().min(2).max(400),
  maxResults: z.number().int().min(1).max(20).optional(),
  domains: z.array(domain).max(10).optional(),
  lang: z.string().regex(/^[a-z]{2}$/).optional(),
});

export const WebFetchBody = z.object({
  url: z.string().url().max(2000),
  maxChars: z.number().int().min(500).max(20000).optional(),
});

export const BusinessSearchBody = z
  .object({
    category: z.string().trim().min(2).max(120),
    location: z.string().trim().min(2).max(160).optional(),
    near: z
      .object({
        lat: z.number().min(-90).max(90),
        lon: z.number().min(-180).max(180),
        radiusM: z.number().int().min(100).max(25000).optional(),
      })
      .optional(),
    limit: z.number().int().min(1).max(50).optional(),
    offset: z.number().int().min(0).max(5000).optional(),
  })
  .refine((b) => b.location || b.near, { message: 'Indica location o near.' });

export const BusinessIdParams = z.object({ id: z.string().regex(/^osm:(node|way|relation):\d{1,15}$/) });
