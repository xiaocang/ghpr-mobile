export function normalizeAction(action: string): string {
  const value = action.trim().toLowerCase();
  switch (value) {
    case "opened":
    case "updated":
    case "review_requested":
    case "commented":
    case "mentioned":
    case "assigned":
    case "merged":
    case "closed":
    case "state_changed":
      return value;
    case "reopened":
    case "ready_for_review":
      return "opened";
    case "synchronize":
    case "edited":
    case "author":
      return "updated";
    case "comment":
      return "commented";
    case "mention":
      return "mentioned";
    case "assign":
      return "assigned";
    case "state_change":
      return "state_changed";
    default:
      return "updated";
  }
}
