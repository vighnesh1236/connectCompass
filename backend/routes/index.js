const express = require("express");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const { z } = require("zod");
const multer = require("multer");
const pool = require("../config/database");
const { notifyUser } = require("../services/realtime");
const { authenticate, requireAdmin } = require("../middleware/auth");
const validate = require("../middleware/validate");

const router = express.Router();
const assignmentPdfUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 10 * 1024 * 1024, files: 1 },
  fileFilter(_req, file, callback) {
    callback(null, file.mimetype === "application/pdf");
  },
});
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
  department: z.string().trim().max(120).optional().default(""),
});
const examSchema = z.object({
  department: z.string().trim().max(120).optional().default(""),
  examType: z.enum(["Mid Semester 1", "Mid Semester 2", "Final Semester"]),
  examDate: z.string().date(),
});
const profileSchema = z.object({
  name: z.string().trim().min(2).max(120),
  rollNumber: z.string().trim().min(1).max(50),
  department: z.string().trim().min(2).max(120),
});
const profilePhotoSchema = z.object({
  profilePhoto: z.string()
    .max(700_000)
    .regex(/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/)
    .nullable(),
});
const directConversationSchema = z.object({
  studentId: z.number().int().positive(),
});
const directMessageSchema = z.object({
  body: z.string().trim().min(1).max(4000),
});

const publicUser = (row) => ({
  id: Number(row.id),
  name: row.name,
  email: row.email,
  rollNumber: row.roll_number,
  department: row.department,
  role: row.role,
  ...(Object.hasOwn(row, "profile_photo") ? { profilePhoto: row.profile_photo } : {}),
});
const userQuery = "id, name, email, roll_number, department, role";
const profileUserQuery = `${userQuery}, profile_photo`;
const asyncRoute = (handler) => (req, res, next) =>
  Promise.resolve(handler(req, res, next)).catch(next);
const requireStudent = (req, res, next) => {
  if (req.user.role !== "student") {
    return res.status(403).json({ message: "Student messaging is only available to student accounts" });
  }
  return next();
};
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
  const { rows } = await pool.query(`SELECT ${profileUserQuery} FROM users WHERE id = $1`, [req.user.id]);
  if (!rows[0]) return res.status(404).json({ message: "Student not found" });
  res.json(publicUser(rows[0]));
}));

router.put("/students/profile", authenticate, validate(profileSchema), asyncRoute(async (req, res) => {
  const { name, rollNumber, department } = req.validatedBody;
  try {
    const { rows } = await pool.query(
      `UPDATE users SET name = $1, roll_number = $2, department = $3
       WHERE id = $4 RETURNING ${profileUserQuery}`,
      [name, rollNumber, department, req.user.id]
    );
    if (!rows[0]) return res.status(404).json({ message: "Student not found" });
    res.json(publicUser(rows[0]));
  } catch (error) {
    if (error.code === "23505") return res.status(409).json({ message: "Roll number is already in use" });
    throw error;
  }
}));

router.put("/students/profile/photo", authenticate, validate(profilePhotoSchema),
  asyncRoute(async (req, res) => {
    const { rows } = await pool.query(
      `UPDATE users SET profile_photo = $1 WHERE id = $2
       RETURNING ${profileUserQuery}`,
      [req.validatedBody.profilePhoto, req.user.id]
    );
    if (!rows[0]) return res.status(404).json({ message: "Student not found" });
    res.json(publicUser(rows[0]));
  }));

router.delete("/students/profile/photo", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `UPDATE users SET profile_photo = NULL WHERE id = $1
     RETURNING ${profileUserQuery}`,
    [req.user.id]
  );
  if (!rows[0]) return res.status(404).json({ message: "Student not found" });
  res.json(publicUser(rows[0]));
}));

router.get("/messages/students", authenticate, requireStudent, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `SELECT id, name, department
     FROM users WHERE role = 'student' AND id != $1
     ORDER BY name`,
    [req.user.id]
  );
  res.json(rows.map((row) => ({ ...row, id: Number(row.id) })));
}));

router.get("/messages/conversations", authenticate, requireStudent, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `SELECT c.id, peer.id AS "peerUserId", peer.name AS "peerName",
       peer.department AS "peerDepartment",
       latest.body AS "lastMessage", latest.created_at AS "lastMessageAt",
       (SELECT COUNT(*) FROM direct_messages unread
        WHERE unread.conversation_id = c.id AND unread.sender_id != $1
          AND unread.read_at IS NULL) AS "unreadCount"
     FROM conversations c
     JOIN users peer ON peer.id = CASE
       WHEN c.participant_one = $1 THEN c.participant_two ELSE c.participant_one END
     LEFT JOIN LATERAL (
       SELECT body, created_at FROM direct_messages
       WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1
     ) latest ON TRUE
     WHERE c.participant_one = $1 OR c.participant_two = $1
     ORDER BY COALESCE(latest.created_at, c.created_at) DESC`,
    [req.user.id]
  );
  res.json(rows.map((row) => ({
    ...row, id: Number(row.id), peerUserId: Number(row.peerUserId),
    unreadCount: Number(row.unreadCount),
  })));
}));

router.post("/messages/conversations", authenticate, requireStudent, validate(directConversationSchema),
  asyncRoute(async (req, res) => {
    const studentId = req.validatedBody.studentId;
    if (studentId === Number(req.user.id)) {
      return res.status(400).json({ message: "You cannot start a conversation with yourself" });
    }
    const student = await pool.query(
      "SELECT id FROM users WHERE id = $1 AND role = 'student'",
      [studentId]
    );
    if (!student.rows[0]) return res.status(404).json({ message: "Student not found" });
    const first = Math.min(Number(req.user.id), studentId);
    const second = Math.max(Number(req.user.id), studentId);
    const { rows } = await pool.query(
      `INSERT INTO conversations (participant_one, participant_two)
       VALUES ($1, $2)
       ON CONFLICT (participant_one, participant_two) DO UPDATE
         SET participant_one = EXCLUDED.participant_one
       RETURNING id`,
      [first, second]
    );
    res.status(200).json({ id: Number(rows[0].id) });
  }));

router.param("conversationId", (req, res, next, id) => {
  if (!/^[1-9]\d*$/.test(id)) {
    return res.status(400).json({ message: "Conversation ID must be a positive integer" });
  }
  return next();
});

router.get("/messages/conversations/:conversationId/messages", authenticate,
  requireStudent,
  asyncRoute(async (req, res) => {
    const conversationId = req.params.conversationId;
    const { rows: conversations } = await pool.query(
      `SELECT id FROM conversations WHERE id = $1
       AND (participant_one = $2 OR participant_two = $2)`,
      [conversationId, req.user.id]
    );
    if (!conversations[0]) return res.status(404).json({ message: "Conversation not found" });
    await pool.query(
      `UPDATE direct_messages SET read_at = NOW()
       WHERE conversation_id = $1 AND sender_id != $2 AND read_at IS NULL`,
      [conversationId, req.user.id]
    );
    const { rows } = await pool.query(
      `SELECT id, sender_id AS "senderId", body, created_at AS "createdAt"
       FROM (
         SELECT id, sender_id, body, created_at
         FROM direct_messages WHERE conversation_id = $1
         ORDER BY id DESC LIMIT 100
       ) recent ORDER BY id ASC`,
      [conversationId]
    );
    res.json(rows.map((row) => ({ ...row, id: Number(row.id), senderId: Number(row.senderId) })));
  }));

router.post("/messages/conversations/:conversationId/messages", authenticate, requireStudent,
  validate(directMessageSchema), asyncRoute(async (req, res) => {
    const conversationId = req.params.conversationId;
    const { rows: conversations } = await pool.query(
      `SELECT c.id, sender.name AS sender_name,
         CASE WHEN c.participant_one = $2 THEN c.participant_two ELSE c.participant_one END AS recipient_id
       FROM conversations c JOIN users sender ON sender.id = $2
       WHERE c.id = $1
         AND (participant_one = $2 OR participant_two = $2)`,
      [conversationId, req.user.id]
    );
    const conversation = conversations[0];
    if (!conversation) return res.status(404).json({ message: "Conversation not found" });
    const { rows } = await pool.query(
      `INSERT INTO direct_messages (conversation_id, sender_id, body)
       VALUES ($1, $2, $3)
       RETURNING id, sender_id AS "senderId", body, created_at AS "createdAt"`,
      [conversationId, req.user.id, req.validatedBody.body]
    );
    void notifyUser(Number(conversation.recipient_id), {
      type: "message",
      messageId: Number(rows[0].id),
      title: "New CampusConnect message",
      body: `${conversation.sender_name}: ${req.validatedBody.body}`.slice(0, 1000),
      conversationId: Number(conversationId),
    });
    res.status(201).json({
      ...rows[0], id: Number(rows[0].id), senderId: Number(rows[0].senderId),
    });
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
       COALESCE(s.status, 'pending') AS status, a.department,
       sub.id IS NOT NULL AS "hasSubmission", sub.file_name AS "fileName"
     FROM assignments a
     JOIN users u ON u.id = $1
     LEFT JOIN assignment_status s ON s.assignment_id = a.id AND s.user_id = $1
     LEFT JOIN assignment_submissions sub ON sub.assignment_id = a.id AND sub.user_id = $1
     WHERE a.department IS NULL OR a.department = '' OR LOWER(a.department) = LOWER(u.department)
     ORDER BY a.deadline ASC`,
    [req.user.id]
  );
  res.json(rows);
}));

router.post("/assignments", authenticate, requireAdmin, validate(assignmentSchema),
  asyncRoute(async (req, res) => {
    const { title, subject, description, deadline, department } = req.validatedBody;
    const { rows } = await pool.query(
      `INSERT INTO assignments (title, subject, description, deadline, department)
       VALUES ($1, $2, $3, $4, NULLIF($5, ''))
       RETURNING id, title, subject, description, deadline::text AS deadline, department`,
      [title, subject, description, deadline, department]
    );
    res.status(201).json({ ...rows[0], status: "pending" });
  }));

router.put("/assignments/:id", authenticate, requireAdmin, validate(assignmentSchema),
  asyncRoute(async (req, res) => {
    const { title, subject, description, deadline, department } = req.validatedBody;
    const { rows } = await pool.query(
      `UPDATE assignments SET title = $1, subject = $2, description = $3, deadline = $4,
       department = NULLIF($5, '')
       WHERE id = $6
       RETURNING id, title, subject, description, deadline::text AS deadline, department`,
      [title, subject, description, deadline, department, req.params.id]
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

router.post("/assignments/:id/submission", authenticate, (req, res, next) => {
  assignmentPdfUpload.single("file")(req, res, (error) => {
    if (error instanceof multer.MulterError) {
      return res.status(error.code === "LIMIT_FILE_SIZE" ? 413 : 400)
        .json({ message: error.code === "LIMIT_FILE_SIZE" ? "PDF must be 10 MB or smaller" : "Choose one PDF file" });
    }
    if (error) return next(error);
    next();
  });
}, asyncRoute(async (req, res) => {
  const file = req.file;
  if (!file) return res.status(400).json({ message: "Choose a PDF file to upload" });
  if (file.buffer.subarray(0, 5).toString("ascii") !== "%PDF-") {
    return res.status(400).json({ message: "The selected file is not a valid PDF" });
  }
  const assignmentId = Number(req.params.id);
  const { rows: assignment } = await pool.query(
    `SELECT a.id FROM assignments a JOIN users u ON u.id = $2
     WHERE a.id = $1
       AND (a.department IS NULL OR a.department = '' OR LOWER(a.department) = LOWER(u.department))`,
    [assignmentId, req.user.id]
  );
  if (!assignment[0]) return res.status(404).json({ message: "Assignment not found for your branch" });
  const fileName = file.originalname.replace(/[^\w.-]/g, "_").slice(0, 255) || "assignment.pdf";
  const { rows } = await pool.query(
    `INSERT INTO assignment_submissions (user_id, assignment_id, file_name, file_data)
     VALUES ($1, $2, $3, $4)
     ON CONFLICT (user_id, assignment_id) DO UPDATE
       SET file_name = EXCLUDED.file_name, file_data = EXCLUDED.file_data, uploaded_at = NOW()
     RETURNING id, file_name AS "fileName", uploaded_at AS "uploadedAt"`,
    [req.user.id, assignmentId, fileName, file.buffer]
  );
  res.status(201).json(rows[0]);
}));

router.get("/assignments/:id/submission", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `SELECT s.file_name AS "fileName", s.file_data AS "fileData"
     FROM assignment_submissions s
     JOIN assignments a ON a.id = s.assignment_id
     JOIN users u ON u.id = s.user_id
     WHERE s.user_id = $1 AND s.assignment_id = $2
       AND (a.department IS NULL OR a.department = '' OR LOWER(a.department) = LOWER(u.department))`,
    [req.user.id, req.params.id]
  );
  if (!rows[0]) return res.status(404).json({ message: "No PDF has been uploaded for this assignment" });
  res.type("application/pdf");
  res.set("Content-Disposition", `inline; filename="${rows[0].fileName.replace(/["\\]/g, "_")}"`);
  res.send(rows[0].fileData);
}));

router.get("/exams", authenticate, asyncRoute(async (req, res) => {
  const { rows } = await pool.query(
    `SELECT e.id, e.department, e.exam_type AS "examType", e.exam_date::text AS "examDate"
     FROM exam_schedule e
     JOIN users u ON u.id = $1
     WHERE e.department IS NULL OR e.department = '' OR LOWER(e.department) = LOWER(u.department)
     ORDER BY exam_date, exam_type`,
    [req.user.id]
  );
  res.json(rows);
}));

router.get("/admin/exams", authenticate, requireAdmin, asyncRoute(async (_req, res) => {
  const { rows } = await pool.query(
    `SELECT id, department, exam_type AS "examType", exam_date::text AS "examDate"
     FROM exam_schedule ORDER BY exam_date, exam_type`
  );
  res.json(rows);
}));

router.post("/admin/exams", authenticate, requireAdmin, validate(examSchema), asyncRoute(async (req, res) => {
  const { department, examType, examDate } = req.validatedBody;
  const { rows } = await pool.query(
    `INSERT INTO exam_schedule (department, exam_type, exam_date)
     VALUES (NULLIF($1, ''), $2, $3)
     RETURNING id, department, exam_type AS "examType", exam_date::text AS "examDate"`,
    [department, examType, examDate]
  );
  res.status(201).json(rows[0]);
}));

router.put("/admin/exams/:id", authenticate, requireAdmin, validate(examSchema), asyncRoute(async (req, res) => {
  const { department, examType, examDate } = req.validatedBody;
  const { rows } = await pool.query(
    `UPDATE exam_schedule SET department = NULLIF($1, ''), exam_type = $2, exam_date = $3
     WHERE id = $4
     RETURNING id, department, exam_type AS "examType", exam_date::text AS "examDate"`,
    [department, examType, examDate, req.params.id]
  );
  if (!rows[0]) return res.status(404).json({ message: "Exam date not found" });
  res.json(rows[0]);
}));

router.delete("/admin/exams/:id", authenticate, requireAdmin, asyncRoute(async (req, res) => {
  const result = await pool.query("DELETE FROM exam_schedule WHERE id = $1", [req.params.id]);
  if (!result.rowCount) return res.status(404).json({ message: "Exam date not found" });
  res.status(204).end();
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
