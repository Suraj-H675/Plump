package dev.plump.emulator;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
public final class RealCoreDeathProbe {
  static CheckpointStore.Identity id(String con){
    if ("gba".equals(con)) return new CheckpointStore.Identity("gba",
            "c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5",
            "mgba-libretro", "d58ea8a86f30598fbf3958585de3d33028535072", "none",
            "af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c", "x86_64", 1);
    if ("ds".equals(con)) return new CheckpointStore.Identity("ds",
            "6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70",
            "melonds-libretro-ds", "16f127dbb587a73371827ce79689c5d237c4b59b",
            "f394adbacb5722ee97c1b37c8064da9a25818310",
            "e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616", "x86_64", 1);
    throw new IllegalArgumentException("Unsupported console: " + con);
  }
  static void sync(Path p)throws IOException{try(FileChannel c=FileChannel.open(p,StandardOpenOption.READ)){c.force(true);}}
  static void syncFile(File f)throws IOException{sync(f.toPath());}
  static String map(Path p)throws Exception{
    Map<String,String> values=new TreeMap<>();
    try(var entries=Files.newDirectoryStream(p)){
      for(Path entry:entries){
        if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(entry))
          throw new IOException("Nonregular canonical save entry");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(entry));
        values.put(entry.getFileName().toString(),HexFormat.of().formatHex(digest));
      }
    }
    if(values.size()!=3)throw new IOException("Expected two canonical PLSV records and receipt");
    return values.toString();
  }
  public static void main(String[] args)throws Exception{
    if(args.length!=4)throw new IllegalArgumentException("prepare|verify gba|ds acceptedD1Dir scratchRoot");
    String cmd=args[0],con=args[1];Path source=Path.of(args[2]),root=Path.of(args[3]);var identity=id(con);
    var canonical=new GameSaveStore(root.resolve("canonical").toFile(),RealCoreDeathProbe::syncFile);
    var checkpoint=new CheckpointStore(root.resolve("checkpoints"),RealCoreDeathProbe::sync,p->Long.MAX_VALUE,p->{});
    var branches=new HistoricalBranchStore(root.resolve("branches"),RealCoreDeathProbe::sync,p->Long.MAX_VALUE,RealCoreDeathProbe::syncFile,p->{});
    var timelines=new ActiveTimelineStore(root.resolve("timelines"),RealCoreDeathProbe::sync);
    if(cmd.equals("prepare")){
      byte[] latest=Files.readAllBytes(source.resolve("n_latest.bin"));
      canonical.write(identity.romSha256,latest);canonical.write(identity.romSha256,latest);
      Files.writeString(root.resolve("canonical.sha"),map(root.resolve("canonical")),StandardOpenOption.CREATE_NEW);
      CheckpointStore.Record cp=checkpoint.create(identity,CheckpointStore.Capture.supported(1,Files.readAllBytes(source.resolve("s_old.bin")),Files.readAllBytes(source.resolve("n_state.bin"))),CheckpointStore.Kind.MANUAL);
      var branch=branches.create(checkpoint,cp.id,identity);
      timelines.selectCanonical(identity,canonical);
      var pending=timelines.prepareBranch(identity,branches,branch);
      var resolution=timelines.resolveCold(true,identity,canonical,branches);
      if(resolution.classification!=ActiveTimelineStore.Classification.AMBIGUOUS)throw new AssertionError("Expected PREPARED ambiguous, got "+resolution.classification);
      Files.writeString(root.resolve("branch.txt"),branch.id,StandardOpenOption.CREATE_NEW);
      Files.writeString(root.resolve("checkpoint.txt"),cp.id,StandardOpenOption.CREATE_NEW);
      System.out.println("PASS REAL CORE PREPARED RESTORE "+con+" source checkpoint verified; N_latest canonical durable, branch selector PREPARED\n");
    }else if(cmd.equals("verify")){
      var resolution=timelines.resolveCold(true,identity,canonical,branches);
      if(resolution.classification!=ActiveTimelineStore.Classification.AMBIGUOUS)throw new AssertionError("Real core interrupted restore must remain ambiguous, got "+resolution.classification);
      if(!Files.readString(root.resolve("canonical.sha")).equals(map(root.resolve("canonical"))))throw new AssertionError("Canonical PLSV modified");
      String bid=Files.readString(root.resolve("branch.txt"));var branch=branches.open(bid,identity);
      String cid=Files.readString(root.resolve("checkpoint.txt"));
      if(!branch.checkpointId.equals(cid)||!checkpoint.verify(cid,identity).id.equals(cid))throw new AssertionError("Source checkpoint mismatched");
      if(!Arrays.equals(branch.nativeSave(),Files.readAllBytes(source.resolve("n_state.bin"))))throw new AssertionError("Original native mismatch");
      if(!Arrays.equals(branch.machineState(),Files.readAllBytes(source.resolve("s_old.bin"))))throw new AssertionError("Original machine mismatch");
      System.out.println("PASS REAL CORE KILL POST-RESTART "+con+" pointer=AMBIGUOUS, canonical three file SHA identical, immutable machine/native source intact, branch journal intact");
    }else throw new IllegalArgumentException(cmd);
  }
}
