package dev.plump.emulator;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.File;
import java.security.MessageDigest;
public final class JournalCli {
 public static void main(String[] a) throws Exception {
   if(a.length!=4) throw new IllegalArgumentException("write|read directory sha input_or_output");
   GameSaveStore s=new GameSaveStore(new File(a[1]), dir -> {
     try(FileChannel ch=FileChannel.open(dir.toPath(),StandardOpenOption.READ)){ch.force(true);}
   });
   if(a[0].equals("write")) s.write(a[2],Files.readAllBytes(Path.of(a[3])));
   else if(a[0].equals("read")){
     var v=s.load(a[2]);if(v.bytes==null)throw new IllegalStateException("Cannot load: "+v.status+" "+v.warning);
     Files.write(Path.of(a[3]),v.bytes);System.out.println("JOURNAL_READ generation="+v.generation+" length="+v.bytes.length+" status="+v.status);
   } else throw new IllegalArgumentException(a[0]);
 }
}
