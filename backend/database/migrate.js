require("dotenv").config();
const fs = require("node:fs/promises");
const path = require("node:path");
const pool = require("../config/database");

async function migrate() {
  const schema = await fs.readFile(path.join(__dirname, "schema.sql"), "utf8");
  await pool.query(schema);
  console.log("Database schema is up to date.");
}

migrate()
  .catch((error) => {
    console.error("Database migration failed:", error.message);
    process.exitCode = 1;
  })
  .finally(() => pool.end());
