db.metadata.insertOne({
  pod: "checkout-6c8f9",
  service: "checkout-service",
  team: "ecommerce",
  tier: "critical",
});
db.duplication.insertMany([
  {
    view: "operational",
    labels: ["service", "endpoint", "error_type"],
  },
  {
    view: "business",
    labels: ["service", "tier"],
  },
]);
