import { redirect } from "next/navigation";

// The product UI is the glasses web app (public/g); the root just points there.
export default function Home() {
  redirect("/g");
}
