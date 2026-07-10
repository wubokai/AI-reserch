export const dynamic = "force-dynamic";

export function GET() {
  return Response.json(
    {
      status: "UP",
      service: "web",
      dataMode: "MOCK",
      timestamp: new Date().toISOString(),
    },
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
