const express = require("express");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const { z } = require("zod");
const pool = require("../config/database");
const { authenticate, requireAdmin } = require("../middleware/auth");
const validate = require("../middleware/validate");

const router = express.Router();
const registrationSchema = z.object({
  name: z.string().trim().min(2).max(120),
  email: z.string().trim().email().max(254),
  password: z.string().min(8).max(72),
  rollNumber: z.string().trim().min(1).max(50),
  department: z.string().trim().min(2).max(120),
});
const loginSchema = z.object({
  email: z.string().trim().email().max(254),
  password: z.string().min(1).max(72),
});
const announcementSchema = z.object({
  title: z.string().trim().min(3).max(180),
  description: z.string().trim().min(1).max(10000),
  category: z.string().trim().min(1).max(60),
});
const eventSchema = z.object({
  title: z.string().trim().min(3).max(180),
  description: z.string().trim().min(1).max(10000),
  eventDate: z.string().date(),
  eventTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/).optional().default(""),
  venue: z.string().trim().min(1).max(180),
});
const assignmentSchema = z.object({
  title: z.string().trim().min(3).max(180),
  subject: z.string().trim().min(1).max(120),
  description: z.string().trim().min(1).max(10000),
  deadline: z.string().date(),
});
const profileSchema = z.object({
  name: z.string().trim().min(2).max(120),
  rollNumber: z.string().trim().min(1).max(50),
  department: z.string().trim().min(2).max(120),
});

const publicUser = (row) => ({
  id: Number(row.id),
  name: row.name,
  email: row.email,
  rollNumber: row.roll_number,
  department: row.department,
  role: row.role,
});
const userQuery = "id, name, email, roll_number, department, role";
const asyncRoute = (handler) => (req, res, next) =>
  Promise.resolve(handler(req, res, next)).catch(next);

router.param("id", (req, res, next, id) => {
  if (!/^[1-9]\d*$/.test(id)) return res.status(400).json({ message: "ID must be a positive integer" });
  return next();
});

router.post("/auth/register", validate(registrationSchema), asyncRoute(async (req, res) => {
  const { name, email, password, rollNumber, department } = req.validatedBody;
  const hash = await bcrypt.hash(password, 12);
  try {
    const { rows } = await pool.query(
      `INSERT INTO users (name, email, password, roll_number, department)
       VALUES ($1, LOWER($2), $3, $4, $5) RETURNING ${userQuery}`,
      [name, email, hash, rollNumber, department]
    );
    const user = publicUser(rows[0]);
    const token = jwt.sign({ id: user.id, role: user.role }, process.env.JWT_SECRET, {
      expiresIn: process.env.JWT_EXPIRES_IN || "7d",
    });
    res.status(201).json({ token, user });
  } catch (error) {
    if (error.code === "23505") {
      return res.status(409).json({ message: "That email or roll number is already registered" });
    }
    throw error;
  }
}));

router.post("/auth/login", validate(loginSchema), asyncRoute(async (req, res) => {
  const { email, password } = req.validatedBody;
  const { rows } = await pool.query(
    `SELECT ${userQuery}, password FROM users WHERE email = LOWER($1)`,
    [email]
  );
  if (!rows[0] || !(await bcrypt.compare(password, rows[0].password))) {
    return res.status(401).json({ message: "Email or password is incorrect" });
  }
  const user = publicUser(rows[0]);
  const token = jwt.sign({ id: user.id, role: user.role }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || "7d",
  });
  res.json({ token, user });
}));

router.get("/students/profile", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(`SELECT ${userQuery} FROM users WHERE id = $1`, [req.user.id]);
  if (!rows[0]) return res.status(404).json({ message: "Student not found" });
  res.json(publicUser(rows[0]));
}));

router.put("/students/profile", authenticate, validate(profileSchema), asyncRoute(async (req, res) => {
  const { name, rollNumber, department } = req.validatedBody;
  try {
    const { rows } = await pool.query(
      `UPDATE users SET name = $1, roll_number = $2, department = $3
       WHERE id = $4 RETURNING ${userQuery}`,
      [name, rollNumber, department, req.user.id]
    );
    if (!rows[0]) return res.status(404).json({ message: "Student not found" });
    res.json(publicUser(rows[0]));
  } catch (error) {
    if (error.code === "23505") return res.status(409).json({ message: "Roll number is already in use" });
    throw error;
  }
}));

router.get("/announcements", authenticate, asyncRoute(async (_req, res) => {
  const { rows } = await pool.query(
    `SELECT id, title, description, category, created_at AS "createdAt"
     FROM announcements ORDER BY created_at DESC`
  );
  res.json(rows);
}));

router.post("/announcements", authenticate, requireAdmin, validate(announcementSchema),
  asyncRoute(async (req, res) => {
    const { title, description, category } = req.validatedBody;
    const { rows } = await pool.query(
      `INSERT INTO announcements (title, description, category)
       VALUES ($1, $2, $3)
       RETURNING id, title, description, category, created_at AS "createdAt"`,
      [title, description, category]
    );
    res.status(201).json(rows[0]);
  }));

router.put("/announcements/:id", authenticate, requireAdmin, validate(announcementSchema),
  asyncRoute(async (req, res) => {
    const { title, description, category } = req.validatedBody;
    const { rows } = await pool.query(
      `UPDATE announcements SET title = $1, description = $2, category = $3
       WHERE id = $4 RETURNING id, title, description, category, created_at AS "createdAt"`,
      [title, description, category, req.params.id]
    );
    if (!rows[0]) return res.status(404).json({ message: "Announcement not found" });
    res.json(rows[0]);
  }));

router.delete("/announcements/:id", authenticate, requireAdmin, asyncRoute(async (req, res) => {
  const result = await pool.query("DELETE FROM announcements WHERE id = $1", [req.params.id]);
  if (!result.rowCount) return res.status(404).json({ message: "Announcement not found" });
  res.status(204).end();
}));

const eventSelect = (userId) => `
  SELECT e.id, e.title, e.description, e.event_date::text AS "eventDate",
         e.event_time::text AS "eventTime", e.venue,
         EXISTS (SELECT 1 FROM event_registrations r WHERE r.event_id = e.id AND r.user_id = $1)
           AS registered
  FROM events e`;

router.get("/events", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(`${eventSelect(req.user.id)} ORDER BY e.event_date ASC`, [req.user.id]);
  res.json(rows);
}));

router.get("/events/my-registrations", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `${eventSelect(req.user.id)}
     JOIN event_registrations r ON r.event_id = e.id AND r.user_id = $1
     ORDER BY e.event_date ASC`,
    [req.user.id]
  );
  res.json(rows);
}));

router.get("/events/:id", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(`${eventSelect(req.user.id)} WHERE e.id = $2`, [req.user.id, req.params.id]);
  if (!rows[0]) return res.status(404).json({ message: "Event not found" });
  res.json(rows[0]);
}));

router.post("/events", authenticate, requireAdmin, validate(eventSchema), asyncRoute(async (req, res) => {
  const { title, description, eventDate, eventTime, venue } = req.validatedBody;
  const { rows } = await pool.query(
    `INSERT INTO events (title, description, event_date, event_time, venue)
     VALUES ($1, $2, $3, NULLIF($4, '')::time, $5)
     RETURNING id, title, description, event_date::text AS "eventDate",
               event_time::text AS "eventTime", venue`,
    [title, description, eventDate, eventTime, venue]
  );
  res.status(201).json({ ...rows[0], registered: false });
}));

router.put("/events/:id", authenticate, requireAdmin, validate(eventSchema), asyncRoute(async (req, res) => {
  const { title, description, eventDate, eventTime, venue } = req.validatedBody;
  const { rows } = await pool.query(
    `UPDATE events SET title = $1, description = $2, event_date = $3,
       event_time = NULLIF($4, '')::time, venue = $5
     WHERE id = $6
     RETURNING id, title, description, event_date::text AS "eventDate",
               event_time::text AS "eventTime", venue`,
    [title, description, eventDate, eventTime, venue, req.params.id]
  );
  if (!rows[0]) return res.status(404).json({ message: "Event not found" });
  res.json({ ...rows[0], registered: false });
}));

router.delete("/events/:id", authenticate, requireAdmin, asyncRoute(async (req, res) => {
  const result = await pool.query("DELETE FROM events WHERE id = $1", [req.params.id]);
  if (!result.rowCount) return res.status(404).json({ message: "Event not found" });
  res.status(204).end();
}));

router.post("/events/:id/register", authenticate, asyncRoute(async (req, res) => {
  try {
    const { rows } = await pool.query(
      `INSERT INTO event_registrations (user_id, event_id) VALUES ($1, $2)
       RETURNING id, user_id AS "userId", event_id AS "eventId", registered_at AS "registeredAt"`,
      [req.user.id, req.params.id]
    );
    res.status(201).json(rows[0]);
  } catch (error) {
    if (error.code === "23505") return res.status(409).json({ message: "You are already registered for this event" });
    if (error.code === "23503") return res.status(404).json({ message: "Event not found" });
    throw error;
  }
}));

router.delete("/events/:id/register", authenticate, asyncRoute(async (req, res) => {
  const result = await pool.query(
    "DELETE FROM event_registrations WHERE user_id = $1 AND event_id = $2",
    [req.user.id, req.params.id]
  );
  if (!result.rowCount) return res.status(404).json({ message: "Registration not found" });
  res.status(204).end();
}));

router.get("/assignments", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `SELECT a.id, a.title, a.subject, a.description, a.deadline::text AS deadline,
       COALESCE(s.status, 'pending') AS status
     FROM assignments a
     LEFT JOIN assignment_status s ON s.assignment_id = a.id AND s.user_id = $1
     ORDER BY a.deadline ASC`,
    [req.user.id]
  );
  res.json(rows);
}));

router.post("/assignments", authenticate, requireAdmin, validate(assignmentSchema),
  asyncRoute(async (req, res) => {
    const { title, subject, description, deadline } = req.validatedBody;
    const { rows } = await pool.query(
      `INSERT INTO assignments (title, subject, description, deadline)
       VALUES ($1, $2, $3, $4)
       RETURNING id, title, subject, description, deadline::text AS deadline`,
      [title, subject, description, deadline]
    );
    res.status(201).json({ ...rows[0], status: "pending" });
  }));

router.put("/assignments/:id", authenticate, requireAdmin, validate(assignmentSchema),
  asyncRoute(async (req, res) => {
    const { title, subject, description, deadline } = req.validatedBody;
    const { rows } = await pool.query(
      `UPDATE assignments SET title = $1, subject = $2, description = $3, deadline = $4
       WHERE id = $5
       RETURNING id, title, subject, description, deadline::text AS deadline`,
      [title, subject, description, deadline, req.params.id]
    );
    if (!rows[0]) return res.status(404).json({ message: "Assignment not found" });
    res.json({ ...rows[0], status: "pending" });
  }));

router.delete("/assignments/:id", authenticate, requireAdmin, asyncRoute(async (req, res) => {
  const result = await pool.query("DELETE FROM assignments WHERE id = $1", [req.params.id]);
  if (!result.rowCount) return res.status(404).json({ message: "Assignment not found" });
  res.status(204).end();
}));

router.put("/assignments/:id/status", authenticate,
  validate(z.object({ status: z.enum(["pending", "completed"]) })),
  asyncRoute(async (req, res) => {
    const { status } = req.validatedBody;
    try {
      const { rows } = await pool.query(
        `INSERT INTO assignment_status (user_id, assignment_id, status, completed_at)
         VALUES ($1, $2, $3, CASE WHEN $3 = 'completed' THEN NOW() ELSE NULL END)
         ON CONFLICT (user_id, assignment_id) DO UPDATE
           SET status = EXCLUDED.status,
               completed_at = CASE WHEN EXCLUDED.status = 'completed' THEN NOW() ELSE NULL END
         RETURNING id, user_id AS "userId", assignment_id AS "assignmentId",
                   status, completed_at AS "completedAt"`,
        [req.user.id, req.params.id, status]
      );
      res.json(rows[0]);
    } catch (error) {
      if (error.code === "23503") return res.status(404).json({ message: "Assignment not found" });
      throw error;
    }
  }));

router.get("/admin/students", authenticate, requireAdmin, asyncRoute(async (_req, res) => {
  const { rows } = await pool.query(
    `SELECT ${userQuery}, created_at AS "createdAt"
     FROM users WHERE role = 'student' ORDER BY name ASC`
  );
  res.json(rows.map(publicUser));
}));

router.get("/admin/events/registrations", authenticate, requireAdmin, asyncRoute(async (_req, res) => {
  const { rows } = await pool.query(
    `SELECT r.id, r.registered_at AS "registeredAt", e.title AS "eventTitle",
       u.name AS "studentName", u.roll_number AS "rollNumber", u.department
     FROM event_registrations r
     JOIN events e ON e.id = r.event_id
     JOIN users u ON u.id = r.user_id
     ORDER BY r.registered_at DESC`
  );
  res.json(rows);
}));

router.get("/admin/dashboard", authenticate, requireAdmin, asyncRoute(async (_req, res) => {
  const { rows } = await pool.query(
    `SELECT
       (SELECT COUNT(*) FROM users WHERE role = 'student') AS students,
       (SELECT COUNT(*) FROM events WHERE event_date >= CURRENT_DATE) AS events,
       (SELECT COUNT(*) FROM assignments WHERE deadline >= CURRENT_DATE) AS assignments,
       (SELECT COUNT(*) FROM announcements) AS announcements`
  );
  res.json(Object.fromEntries(Object.entries(rows[0]).map(([key, value]) => [key, Number(value)])));
}));

module.exports = router;
