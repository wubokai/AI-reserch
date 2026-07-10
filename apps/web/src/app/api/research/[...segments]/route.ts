import { proxyResearchRequest } from "@/lib/server/api-proxy";

export const dynamic = "force-dynamic";

type RouteContext = {
  params: Promise<{ segments: string[] }>;
};

async function forward(request: Request, context: RouteContext) {
  const { segments } = await context.params;
  return proxyResearchRequest(request, segments);
}

export const GET = forward;
export const POST = forward;
export const DELETE = forward;
