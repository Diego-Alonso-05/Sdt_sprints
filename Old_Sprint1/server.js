import express from "express";
import multer from "multer";
import { create } from "ipfs-http-client";

const app = express();
const upload = multer({ storage: multer.memoryStorage() });

// Conncets with local IPFS node
const ipfs = create({ url: "http://127.0.0.1:5001/" });

// Route to upload files
app.post("/upload", upload.single("file"), async (req, res) => {
  try {
    if (!req.file) return res.status(400).send("No se envió ningún archivo");

    const { cid } = await ipfs.add(req.file.buffer);

    res.json({
      mensaje: "Archivo añadido a IPFS correctamente",
      cid: cid.toString(),
    });
  } catch (err) {
    console.error(err);
    res.status(500).send("Error al subir archivo a IPFS");
  }
});

app.listen(3000, () => {
  console.log("API del líder corriendo en http://localhost:3000");
});