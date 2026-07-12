export const dynamic = "force-dynamic";

export function GET() {
  const dataMode = process.env.DATA_MODE === "REAL" ? "REAL" : "MOCK";

  return Response.json(
    {
      status: "UP",
      service: "web",
      dataMode,
      timestamp: new Date().toISOString(),
    },
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
